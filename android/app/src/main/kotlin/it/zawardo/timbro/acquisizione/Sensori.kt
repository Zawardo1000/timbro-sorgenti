package it.zawardo.timbro.acquisizione

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationRequest
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import it.zawardo.timbro.core.Manifest as ManifestTimbratura
import it.zawardo.timbro.core.Posizione
import it.zawardo.timbro.core.ReteVista
import it.zawardo.timbro.core.Wifi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * Posizione e reti Wi-Fi raccolte mentre si registra (docs/PROGETTO.md §19). Partono al tocco di Registra e si
 * chiudono allo stop: si tiene la posizione più precisa arrivata nel frattempo e la scansione Wi-Fi più recente.
 *
 * - posizione: per tutta la ripresa (al massimo 15 s, senza mai allungarla) aggiornamenti continui ad alta precisione
 *   da GPS, "fused" (se c'è, senza Google Play Services) e rete, più l'ultima nota se recente; vince il fix più preciso.
 *   Se il GPS non fa in tempo (al chiuso), resta il migliore della rete. `is_mock` dice se l'ha data un'app di
 *   posizione simulata: Android la marca da solo;
 * - reti: `startScan` (limitato da Android a poche scansioni ogni due minuti) e i risultati più recenti, passati dal
 *   filtro di :core (niente MAC casuali, niente nomi di rete).
 *
 * Senza il permesso della posizione niente posizione e niente reti: la timbratura parte lo stesso.
 */
class RaccoltaSensori(private val contesto: Context) {
    private val lm = contesto.getSystemService(LocationManager::class.java)
    private val wm = contesto.applicationContext.getSystemService(WifiManager::class.java)
    private val esecutore = Executors.newSingleThreadExecutor()
    private val annulla = mutableListOf<CancellationSignal>()
    private val ascoltatori = mutableListOf<LocationListener>()
    @Volatile private var migliore: Location? = null
    private var ricevitore: BroadcastReceiver? = null

    val conPosizione: Boolean
        get() = ContextCompat.checkSelfPermission(contesto, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Precisione della posizione migliore finora (metri), per la pastiglia della registrazione. */
    val precisioneM: Float? get() = migliore?.takeIf { it.hasAccuracy() }?.accuracy

    /** Da dove viene la posizione migliore finora: "gps", "network", "fused" (per la pastiglia). */
    val fonte: String? get() = migliore?.provider

    /** Reti fisse viste finora (già filtrate), per la pastiglia della registrazione. */
    fun retiViste(): Int = runCatching { Wifi.da(null, scansione()).aps.size }.getOrDefault(0)

    @SuppressLint("MissingPermission")
    fun avvia() {
        if (!conPosizione) return
        val adesso = System.currentTimeMillis()
        // ultima posizione nota: vale se di meno di due minuti fa (poi arriva quella fresca)
        for (p in provider()) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                ?.takeIf { adesso - it.time < 120_000 }?.let(::considera)
        }
        // aggiornamenti continui ad alta precisione da tutti i provider: il GPS al primo fix è spesso impreciso e
        // migliora nei secondi successivi; si tiene il migliore (considera) fino allo stop
        for (p in provider()) {
            val ascolta = LocationListener { considera(it) }.also { ascoltatori += it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val richiesta = LocationRequest.Builder(500L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(0L).setMinUpdateDistanceMeters(0f).build()
                runCatching { lm.requestLocationUpdates(p, richiesta, esecutore, ascolta) }
            } else {
                runCatching { lm.requestLocationUpdates(p, 500L, 0f, ascolta, contesto.mainLooper) }
            }
        }
        // in più una lettura immediata (sui telefoni dove il primo aggiornamento tarda)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in provider()) {
                val segnale = CancellationSignal().also { annulla += it }
                runCatching { lm.getCurrentLocation(p, segnale, esecutore) { it?.let(::considera) } }
            }
        }
        // scansione Wi-Fi: Android limita le richieste, ma i risultati dell'ultima scansione restano disponibili
        ricevitore = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = Unit  // basta che arrivi: i risultati si leggono allo stop
        }.also { r ->
            ContextCompat.registerReceiver(contesto, r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        @Suppress("DEPRECATION")
        runCatching { wm?.startScan() }
    }

    private fun provider(): List<String> {
        val tutti = runCatching { lm.allProviders }.getOrDefault(emptyList())
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in tutti) add(LocationManager.FUSED_PROVIDER)
            if (LocationManager.GPS_PROVIDER in tutti) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in tutti) add(LocationManager.NETWORK_PROVIDER)
        }
    }

    @Synchronized
    private fun considera(l: Location) {
        val attuale = migliore
        // a parità di precisione vince il fix più recente (più vicino all'ora della timbratura); lo stesso fix ripetuto
        // da "fused" (stessa ora) non prende il posto di quello del GPS
        val meglio = attuale == null || (l.hasAccuracy() && (!attuale.hasAccuracy() || l.accuracy < attuale.accuracy ||
            (l.accuracy == attuale.accuracy && l.time > attuale.time)))
        if (meglio) migliore = l
    }

    @SuppressLint("MissingPermission")
    private fun scansione(): List<ReteVista> {
        if (!conPosizione) return emptyList()
        return runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList()).map { r ->
            @Suppress("DEPRECATION")
            ReteVista(r.BSSID.orEmpty(), r.level, r.frequency)
        }
    }

    /** Ferma tutto e restituisce posizione e reti per il manifest (scartate le parti non valide: [Posizione.da], [Wifi.da]). */
    @SuppressLint("MissingPermission")
    fun concludi(): Pair<Posizione?, Wifi?> {
        annulla.forEach { runCatching { it.cancel() } }
        annulla.clear()
        ascoltatori.forEach { runCatching { lm.removeUpdates(it) } }
        ascoltatori.clear()
        ricevitore?.let { runCatching { contesto.unregisterReceiver(it) } }
        ricevitore = null
        esecutore.shutdown()
        val posizione = migliore?.let { l ->
            val simulata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider
            Posizione.da(
                l.latitude, l.longitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                fixTime = ManifestTimbratura.oraDispositivo(ZonedDateTime.ofInstant(Instant.ofEpochMilli(l.time), ZoneId.systemDefault())),
                provider = l.provider,
                isMock = simulata,
            )
        }
        val wifi = if (conPosizione) {
            val risultati = runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList())
            // ora della scansione: ScanResult.timestamp è in microsecondi dall'avvio del telefono
            val ultima = risultati.maxOfOrNull { it.timestamp }
            val ora = ultima?.let {
                val fa = SystemClock.elapsedRealtime() - it / 1000
                ManifestTimbratura.oraDispositivo(ZonedDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis() - fa), ZoneId.systemDefault()))
            }
            Wifi.da(ora, scansione()).takeIf { it.aps.isNotEmpty() || ora != null }
        } else {
            null
        }
        return posizione to wifi
    }
}

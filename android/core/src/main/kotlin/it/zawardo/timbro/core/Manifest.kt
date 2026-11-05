package it.zawardo.timbro.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Il manifest della timbratura: il JSON i cui byte esatti il telefono firma e il server conserva (docs/PROGETTO.md
 * §5.4, `timbro/manifest.py` nel backend). Il server non ricanonicalizza nulla: verifica la firma su questi byte.
 *
 * I controlli degli `init` sono gli stessi del server (`timbro/manifest.py`, `timbro/validation.py`): un manifest che
 * il server rifiuterebbe con 422 non si costruisce nemmeno, perché dopo la firma non si potrebbe più correggere.
 * Posizione e reti arrivano da sensori che possono dare valori strani: si costruiscono con [Posizione.da] e
 * [Wifi.da], che scartano le parti non valide (meglio perdere la posizione che la timbratura).
 */
@Serializable
data class Manifest(
    val v: Int = 1,
    @SerialName("client_id") val clientId: String,
    @SerialName("device_id") val deviceId: String,
    val kind: String, // "in" | "out"
    val sha256: String,
    val size: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("device_time") val deviceTime: String, // ISO 8601 con fuso, da [oraDispositivo]
    val location: Posizione? = null,
    val wifi: Wifi? = null,
    val clocks: Orologi? = null,
    @SerialName("app_version") val appVersion: String,
) {
    init {
        require(v == 1) { "v: solo la versione 1" }
        require(UUID.matches(clientId)) { "client_id: UUID minuscolo" }
        require(senzaNul(deviceId)) { "device_id: carattere NUL" }
        require(kind == "in" || kind == "out") { "kind: in o out" }
        require(SHA256.matches(sha256)) { "sha256: 64 caratteri esadecimali minuscoli" }
        require(size in 1..Int.MAX_VALUE) { "size fuori intervallo" }
        require(durationMs in 0..Int.MAX_VALUE) { "duration_ms fuori intervallo" }
        require(testoBreve(deviceTime)) { "device_time: al massimo $MAX_TESTO caratteri, senza NUL" }
        require(testoBreve(appVersion)) { "app_version: al massimo $MAX_TESTO caratteri, senza NUL" }
    }

    /** I byte da firmare e inviare: JSON compatto, campi nulli omessi. */
    fun bytes(): ByteArray = JSON.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val JSON = Json { encodeDefaults = true; explicitNulls = false }

        /**
         * Sempre millisecondi e fuso numerico (`2026-11-05T07:58:12.431+01:00`, 29 caratteri). Non `toString()`:
         * quello di ZonedDateTime aggiunge `[Europe/Rome]` (42 caratteri, oltre il limite del server) e quello di
         * OffsetDateTime cambia le cifre decimali secondo l'istante.
         */
        private val ORA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

        fun oraDispositivo(t: ZonedDateTime): String = ORA.format(t)
    }
}

@Serializable
data class Posizione(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("fix_time") val fixTime: String? = null,
    val provider: String? = null,
    @SerialName("is_mock") val isMock: Boolean? = null,
) {
    init {
        require(lat in -90.0..90.0) { "lat fuori intervallo" } // NaN non sta in nessun intervallo
        require(lon in -180.0..180.0) { "lon fuori intervallo" }
        require(accuracyM == null || accuracyM.isFinite()) { "accuracy_m non finita" }
        require(fixTime == null || senzaNul(fixTime)) { "fix_time: carattere NUL" }
        require(provider == null || senzaNul(provider)) { "provider: carattere NUL" }
    }

    companion object {
        /** Dalla posizione del telefono: null se latitudine o longitudine non valgono, i campi accessori sbagliati via. */
        fun da(lat: Double, lon: Double, accuracyM: Double? = null, fixTime: String? = null, provider: String? = null, isMock: Boolean? = null): Posizione? {
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return Posizione(
                lat, lon,
                accuracyM = accuracyM?.takeIf { it.isFinite() && it >= 0 },
                fixTime = fixTime?.takeIf(::testoBreve),
                provider = provider?.takeIf(::testoBreve),
                isMock = isMock,
            )
        }
    }
}

@Serializable
data class Wifi(
    @SerialName("scan_time") val scanTime: String? = null,
    val aps: List<AccessPoint> = emptyList(),
) {
    init {
        require(scanTime == null || testoBreve(scanTime)) { "scan_time: al massimo $MAX_TESTO caratteri, senza NUL" }
        require(aps.size <= Reti.MASSIMO) { "al massimo ${Reti.MASSIMO} reti" }
    }

    companion object {
        /** Dalla scansione del telefono: reti filtrate da [Reti.perManifest], ora della scansione tolta se non vale. */
        fun da(scanTime: String?, scansione: List<ReteVista>): Wifi = Wifi(scanTime?.takeIf(::testoBreve), Reti.perManifest(scansione))
    }
}

/** Un access point: solo l'indirizzo (BSSID), mai il nome della rete. */
@Serializable
data class AccessPoint(val bssid: String, val rssi: Int? = null, val mhz: Int? = null) {
    init {
        require(Reti.valido(bssid)) { "bssid: aa:bb:cc:dd:ee:ff minuscolo" }
        require(rssi == null || rssi in Reti.RSSI) { "rssi fuori intervallo" }
        require(mhz == null || mhz in Reti.MHZ) { "mhz fuori intervallo" }
    }
}

/** Una rete come la restituisce la scansione di Android, prima di qualunque controllo. */
data class ReteVista(val bssid: String, val rssi: Int? = null, val mhz: Int? = null)

/** SHA-256 esadecimale minuscolo. */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Limite dei campi di testo corti del server (`max_length=40`). */
/**
 * Orologi che chi usa il telefono non può spostare dalle impostazioni, riportati all'inizio della ripresa: confermano
 * (o smentiscono) device_time, che invece si cambia a mano (docs/PROGETTO.md §24, `Clocks` in `timbro/manifest.py`).
 * Ora di rete (NTP o rete cellulare, Android 13+), ora satellitare (GNSS) e se l'ora del telefono era automatica.
 */
@Serializable
data class Orologi(
    @SerialName("auto_time") val autoTime: Boolean? = null,
    val network: String? = null,
    val gnss: String? = null,
) {
    init {
        require(network == null || testoBreve(network)) { "clocks.network: al massimo $MAX_TESTO caratteri, senza NUL" }
        require(gnss == null || testoBreve(gnss)) { "clocks.gnss: al massimo $MAX_TESTO caratteri, senza NUL" }
    }
}

const val MAX_TESTO = 40

/** Postgres non accetta il carattere NUL nei testi: il server lo rifiuta (`NoNulStr`). */
internal fun senzaNul(s: String): Boolean = '\u0000' !in s

internal fun testoBreve(s: String): Boolean = s.length <= MAX_TESTO && senzaNul(s)

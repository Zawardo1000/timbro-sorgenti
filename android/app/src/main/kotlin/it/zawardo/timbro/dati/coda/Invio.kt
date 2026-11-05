package it.zawardo.timbro.dati.coda

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.zawardo.timbro.R
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.acquisizione.cartellaVideo
import it.zawardo.timbro.core.Ritentativi
import it.zawardo.timbro.core.StatoInvio
import it.zawardo.timbro.core.api.ApiJson
import it.zawardo.timbro.core.api.StatoTimbratura
import it.zawardo.timbro.dati.ErroreHttp
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Svuota la coda delle timbrature quando c'è rete (WorkManager): vedi [Invio]. */
class InvioWorker(contesto: Context, parametri: WorkerParameters) : CoroutineWorker(contesto, parametri) {
    override suspend fun doWork(): Result {
        Invio.esegui(applicationContext as TimbroApp)
        return Result.success()
    }
}

/**
 * Invio della coda (docs/PROGETTO.md §20, §22): per ogni timbratura prima il manifest (`POST /punches`, idempotente sul
 * client_id), poi il video (`PUT /punches/{id}/video`), che si cancella dal telefono solo quando il server lo ha
 * ricevuto. Poi si chiede al server lo stato delle prove (marca qualificata, attestazione, Bitcoin) per la catena di
 * stato di Timbra.
 *
 * - rifiuti definitivi (409 client_id già usato con byte diversi, 413 video troppo grande, 422 manifest o video non
 *   validi): niente altri tentativi e un avviso;
 * - rete, 5xx e il resto: si ritenta con le attese di [Ritentativi] (fino a un'ora, poi ogni 6 ore dopo due giorni),
 *   come le marche sul server: un errore temporaneo non rende mai definitivo l'invio.
 *
 * Un solo invio alla volta nel processo ([mutex]): il lavoro "subito" (nuova timbratura, apertura dell'app) e quello
 * "più tardi" (ritentativi, stato delle prove) non mandano due volte la stessa cosa (e il server è comunque idempotente).
 */
object Invio {
    private const val TAG = "timbro.invio"
    private const val SUBITO = "invio"
    private const val PIU_TARDI = "invio-ritenta"
    private const val CANALE = "timbrature"
    private val mutex = Mutex()

    /** Appena c'è rete: nuova timbratura in coda, o l'app che si apre. */
    fun pianifica(contesto: Context) = accoda(contesto, SUBITO, Duration.ZERO, ExistingWorkPolicy.KEEP)

    /**
     * Dissociazione: toglie gli invii programmati ed esegue [blocco] quando non c'è un invio in corso (lo aspetta al
     * massimo 15 s: una chiamata di rete bloccata non deve impedire di dissociare). Dopo, [esegui] non fa più niente
     * perché il telefono non è associato.
     */
    suspend fun fermaPer(contesto: Context, blocco: suspend () -> Unit) {
        WorkManager.getInstance(contesto).run {
            cancelUniqueWork(SUBITO)
            cancelUniqueWork(PIU_TARDI)
        }
        val preso = withTimeoutOrNull(15_000) { mutex.lock() } != null
        try {
            blocco()
        } finally {
            if (preso) mutex.unlock()
        }
    }

    private fun accoda(contesto: Context, nome: String, ritardo: Duration, politica: ExistingWorkPolicy) {
        val richiesta = OneTimeWorkRequestBuilder<InvioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(ritardo.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(contesto).enqueueUniqueWork(nome, politica, richiesta)
    }

    suspend fun esegui(app: TimbroApp) = mutex.withLock {
        if (!app.associazione.associato) return@withLock
        val dao = app.coda.coda()
        for (t in dao.daInviare(System.currentTimeMillis())) invia(app, dao, t)
        aggiornaProve(app, dao)
        pulisciVideo(app, dao)
        ripianifica(app, dao)
    }

    private suspend fun invia(app: TimbroApp, dao: CodaDao, iniziale: TimbraturaInCoda) {
        var t = iniziale
        try {
            if (t.stato == StatoInvio.DA_INVIARE) {
                val catena = t.attestazione?.let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }
                val s = app.api.inviaTimbratura(t.manifest, t.firma, catena)
                dao.avanza(t.id, if (s.videoRicevuto) StatoInvio.COMPLETA else StatoInvio.VIDEO_DA_INVIARE, s.id)
                salvaProve(dao, t.id, s)
                if (s.videoRicevuto) File(t.percorsoVideo).delete()
                Log.i(TAG, "timbratura ${t.clientId} accettata dal server (id ${s.id})")
                t = dao.perId(t.id) ?: return
            }
            if (t.stato == StatoInvio.VIDEO_DA_INVIARE) {
                val file = File(t.percorsoVideo)
                val serverId = t.serverId ?: return
                if (!file.exists()) {
                    dao.rifiuta(t.id, "video non più presente sul telefono")
                    avvisa(app, "Video non inviato", "Il video della timbratura non c'è più sul telefono: restano impronta, firma e marche.")
                    return
                }
                val s = app.api.inviaVideo(serverId, file)
                salvaProve(dao, t.id, s)
                if (s.videoRicevuto) {
                    dao.avanza(t.id, StatoInvio.COMPLETA, s.id)
                    file.delete()
                    Log.i(TAG, "video di ${t.clientId} ricevuto dal server")
                } else {
                    ritenta(dao, t, "video non registrato dal server")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErroreHttp) {
            if (Ritentativi.definitivo(e.codice)) {
                dao.rifiuta(t.id, e.message ?: "HTTP ${e.codice}")
                Log.w(TAG, "timbratura ${t.clientId} rifiutata: ${e.message}")
                avvisa(app, "Timbratura rifiutata dal server", e.dettaglio ?: "Errore ${e.codice}: la timbratura non verrà reinviata.")
            } else {
                ritenta(dao, t, e.message ?: "HTTP ${e.codice}")
            }
        } catch (e: Exception) {
            ritenta(dao, t, "${e.javaClass.simpleName}: ${e.message}".take(300))
        }
    }

    private suspend fun ritenta(dao: CodaDao, t: TimbraturaInCoda, errore: String) {
        val adesso = System.currentTimeMillis()
        val attesa = Ritentativi.attesa(t.tentativi + 1, Duration.ofMillis(adesso - t.creataIl))
        dao.ritenta(t.id, adesso + attesa.toMillis(), errore)
        Log.w(TAG, "timbratura ${t.clientId}: $errore, nuovo tentativo fra ${attesa.seconds} s")
    }

    private suspend fun salvaProve(dao: CodaDao, id: Long, s: StatoTimbratura) {
        dao.aggiornaProve(
            id, s.marcataQualificata, s.videoRicevuto, s.attestation?.ok,
            s.attestation?.problems?.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(1000),
            s.confermataSuBitcoin, System.currentTimeMillis(),
        )
    }

    /** Stato delle prove delle timbrature recenti già arrivate (marca, attestazione, video, Bitcoin). */
    private suspend fun aggiornaProve(app: TimbroApp, dao: CodaDao) {
        val dal = System.currentTimeMillis() - Duration.ofDays(7).toMillis()
        for (t in dao.conProvePendenti(dal, 10)) {
            val serverId = t.serverId ?: continue
            try {
                salvaProve(dao, t.id, app.api.statoTimbratura(serverId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stato della timbratura $serverId non letto: ${e.message}")
            }
        }
    }

    /** Video rimasti senza timbratura (registrazione annullata a metà, processo morto): via dopo un'ora. */
    private suspend fun pulisciVideo(app: TimbroApp, dao: CodaDao) {
        val usati = dao.tutte().map { File(it.percorsoVideo).name }.toSet()
        val limite = System.currentTimeMillis() - Duration.ofHours(1).toMillis()
        cartellaVideo(app).listFiles()?.filter { it.name !in usati && it.lastModified() < limite }?.forEach { it.delete() }
    }

    private suspend fun ripianifica(app: TimbroApp, dao: CodaDao) {
        val adesso = System.currentTimeMillis()
        val prossimo = dao.prossimoTentativo()
        val pendenti = dao.conProvePendenti(adesso - Duration.ofDays(7).toMillis(), 50)
        val ritardo = when {
            prossimo != null -> Duration.ofMillis((prossimo - adesso).coerceAtLeast(0))
            // marca, video o attestazione appena chiesti: il server li completa in pochi secondi
            pendenti.any { (!it.marcata || !it.videoRicevuto || (it.attestazione != null && it.integro == null)) && adesso - it.creataIl < 3_600_000 } -> Duration.ofSeconds(30)
            // resta solo Bitcoin (ore): un'occhiata ogni ora
            pendenti.isNotEmpty() -> Duration.ofHours(1)
            else -> return
        }
        accoda(app, PIU_TARDI, ritardo, ExistingWorkPolicy.REPLACE)
    }

    /** Avviso per un problema che non si risolve da solo (rifiuto definitivo, video perso). */
    private fun avvisa(contesto: Context, titolo: String, testo: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(contesto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = contesto.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANALE, "Invio delle timbrature", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(contesto, CANALE)
            .setSmallIcon(R.drawable.ic_sveglia)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(contesto).notify(titolo.hashCode(), n) }
    }
}

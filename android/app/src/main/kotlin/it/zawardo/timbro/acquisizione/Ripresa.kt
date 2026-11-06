package it.zawardo.timbro.acquisizione

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import android.util.Range
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import it.zawardo.timbro.core.Orologi
import it.zawardo.timbro.core.Posizione
import it.zawardo.timbro.core.Wifi
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Una registrazione finita, in attesa della conferma: file nella cartella privata dell'app (fuori dal backup), sua
 * impronta SHA-256 calcolata subito dopo lo stop, posizione e reti raccolte durante la ripresa. L'ora della timbratura è
 * l'inizio della registrazione: è quella che il display del terminale mostra nel video.
 */
data class Ripresa(
    val percorso: String,
    val sha256: String,
    val dimensione: Long,
    val durataMs: Long,
    val inizio: ZonedDateTime,
    val posizione: Posizione?,
    val wifi: Wifi?,
    val orologi: Orologi? = null,
    /** Codifica usata ("CameraX", "c2.mtk.avc.encoder · CBR"): solo da mostrare, non entra nel manifest. */
    val codifica: String? = null,
) {
    fun cancella() {
        File(percorso).delete()
    }
}

/** Cartella dei video: privata, esclusa dal backup (noBackupFilesDir), come la coda (docs/PROGETTO.md §20). */
fun cartellaVideo(contesto: Context): File = File(contesto.noBackupFilesDir, "video").apply { mkdirs() }

/** SHA-256 del file in streaming (anche 50 MB senza tenerli in memoria), esadecimale minuscolo. */
suspend fun impronta(file: File): String = withContext(Dispatchers.IO) {
    val md = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream().buffered(), md).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) Unit
    }
    md.digest().joinToString("") { "%02x".format(it) }
}

/** Durata dal contenitore MP4; se non si legge, quella misurata durante la registrazione. */
fun durataMs(file: File, misurataMs: Long): Long = runCatching {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(file.absolutePath)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
}.getOrNull() ?: misurataMs

/** Quello che succede durante una ripresa: la durata registrata finora, poi la fine. */
sealed interface EventoRipresa {
    data class Durata(val ms: Long) : EventoRipresa

    /** [riuscita]: fermata a mano o al limite dei 15 secondi, con il file completo; altrimenti [errore]. */
    data class Fine(val riuscita: Boolean, val errore: String? = null) : EventoRipresa
}

/**
 * La fotocamera per la timbratura: anteprima e registrazione 720p H.264 **senza audio** (il microfono non si chiede
 * nemmeno: docs/PROGETTO.md §3.1), fine automatica a [DURATA_MASSIMA_MS]. Fotocamera posteriore, quella che si punta sul
 * terminale; se non c'è, l'altra. Due codifiche: quella di CameraX ([RegistratoreCameraX]) e, in prova, quella compatta
 * a bitrate costante ([RegistratoreCompatto]), scelta nelle impostazioni.
 */
interface Registratore {
    /** Collega anteprima e registrazione al ciclo di vita della schermata. */
    suspend fun collega(proprietario: LifecycleOwner, anteprima: PreviewView)

    /** Avvia la registrazione in un file nuovo; gli eventi arrivano a [onEvento] sul thread principale. */
    fun avvia(onEvento: (EventoRipresa) -> Unit): File

    fun ferma()

    /** La schermata se ne va: si liberano le risorse proprie (il codificatore della codifica compatta). */
    fun chiudi() {}

    /** La codifica usata nell'ultima ripresa, per la schermata dopo lo stop. */
    val descrizione: String

    companion object {
        const val DURATA_MASSIMA_MS = 15_000L

        /** Quello scelto nelle impostazioni; senza un codificatore H.264 a bitrate costante, quello di CameraX. */
        fun crea(contesto: Context): Registratore =
            if (PreferenzaVideo.compatto(contesto) && RegistratoreCompatto.supportato()) RegistratoreCompatto(contesto)
            else RegistratoreCameraX(contesto)
    }
}

/** Scelta della codifica (in prova): preferenza del telefono, spenta finché la compatta non è provata. */
object PreferenzaVideo {
    private fun prefs(contesto: Context) = contesto.getSharedPreferences("video", Context.MODE_PRIVATE)

    fun compatto(contesto: Context): Boolean = prefs(contesto).getBoolean("compatto", false)

    fun impostaCompatto(contesto: Context, acceso: Boolean) = prefs(contesto).edit { putBoolean("compatto", acceso) }
}

/** Bitrate obiettivo del video (bit/s): il codificatore lo tiene come media, la scena ferma ne usa anche meno. */
const val BITRATE_VIDEO = 1_000_000

const val FOTOGRAMMI_AL_SECONDO = 15

internal fun fotocamera(provider: ProcessCameraProvider): CameraSelector = when {
    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> error("nessuna fotocamera disponibile")
}

/** Il Recorder di CameraX con le scelte di sempre: 720p (o la qualità più vicina) e l'obiettivo di bitrate. */
internal fun recorderDiSempre(): Recorder = Recorder.Builder()
    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
    // display e badge, inquadratura quasi ferma: 1 Mbit/s di obiettivo (docs/PROGETTO.md §5.3; sul POCO X6 Pro
    // 1,5 di obiettivo davano ~2,6 effettivi). Il predefinito di CameraX per l HD è ~9 Mbit/s: 7 MB per 6 s
    .setTargetVideoEncodingBitRate(BITRATE_VIDEO)
    .build()

/**
 * La codifica di CameraX (Recorder). Il codificatore resta in VBR, dove da Android 12 vale una soglia di qualità che
 * alza il bitrate: sul POCO X6 Pro ~3 Mbit/s qualunque obiettivo (vedi [RegistratoreCompatto]).
 */
class RegistratoreCameraX(private val contesto: Context) : Registratore {
    override val descrizione = "CameraX"
    private var cattura: VideoCapture<Recorder>? = null
    private var registrazione: Recording? = null

    override suspend fun collega(proprietario: LifecycleOwner, anteprima: PreviewView) {
        val provider = ProcessCameraProvider.awaitInstance(contesto)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(anteprima.surfaceProvider) }
        // 15 fotogrammi al secondo: per un display e un badge fermi bastano, e il codificatore (che sotto ~2,6 Mbit/s a
        // 30 fps non scende, qualunque obiettivo gli si dia) usa meno bit, senza perdere nitidezza
        val vc = VideoCapture.Builder(recorderDiSempre()).setTargetFrameRate(Range(FOTOGRAMMI_AL_SECONDO, FOTOGRAMMI_AL_SECONDO)).build()
        provider.unbindAll()
        provider.bindToLifecycle(proprietario, fotocamera(provider), preview, vc)
        cattura = vc
    }

    @SuppressLint("MissingPermission") // nessun audio: withAudioEnabled non si chiama mai
    override fun avvia(onEvento: (EventoRipresa) -> Unit): File {
        val vc = cattura ?: error("fotocamera non collegata")
        val file = File(cartellaVideo(contesto), "${UUID.randomUUID()}.mp4")
        val opzioni = FileOutputOptions.Builder(file).setDurationLimitMillis(Registratore.DURATA_MASSIMA_MS).build()
        registrazione = vc.output.prepareRecording(contesto, opzioni).start(ContextCompat.getMainExecutor(contesto)) { e ->
            when (e) {
                is VideoRecordEvent.Status -> onEvento(EventoRipresa.Durata(e.recordingStats.recordedDurationNanos / 1_000_000))
                is VideoRecordEvent.Finalize ->
                    onEvento(if (riuscita(e)) EventoRipresa.Fine(true) else EventoRipresa.Fine(false, "errore ${e.error}"))
                else -> Unit
            }
        }
        return file
    }

    override fun ferma() {
        registrazione?.stop()
        registrazione = null
    }

    private companion object {
        /** La registrazione è finita bene: fermata a mano o al limite dei 15 secondi. */
        fun riuscita(fine: VideoRecordEvent.Finalize): Boolean =
            !fine.hasError() || fine.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
    }
}

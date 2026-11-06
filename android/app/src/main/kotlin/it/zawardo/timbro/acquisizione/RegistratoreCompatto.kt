package it.zawardo.timbro.acquisizione

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

private const val TAG = "Timbro.Codifica"

/**
 * Codifica compatta (predefinita dal 06/11/2026, ripiego su CameraX in [CodificaVideo]): stessa fotocamera, stessa risoluzione scelta da CameraX (720p) e 15 fotogrammi al
 * secondo, senza audio, ma il video lo codifica un MediaCodec H.264 nostro **a bitrate costante (CBR)**, con un
 * fotogramma chiave ogni [SECONDI_TRA_CHIAVI] secondi.
 *
 * Perché: il Recorder di CameraX lascia il codificatore in VBR, dove da Android 12 vale una soglia di qualità che alza
 * il bitrate oltre l'obiettivo (sul POCO X6 Pro ~3 Mbit/s qualunque obiettivo e a qualunque frequenza), e mette un
 * fotogramma chiave al secondo, che in una scena ferma pesa più di tutto il resto. In CBR la soglia non si applica
 * (documentazione di MediaCodec, "quality floor").
 *
 * Collegamento con CameraX: [UscitaCompatta], un VideoOutput, l'aggancio previsto per i codificatori propri.
 * Risoluzione e capacità le decide il Recorder di sempre ([recorderDiSempre]), a cui l'uscita le delega; solo la
 * superficie dei fotogrammi va al nostro codificatore. Usa API riservate di CameraX (MediaSpec, Observable, Timebase): con la versione fissata nel
 * catalogo funzionano, a ogni aggiornamento di CameraX va riprovata.
 */
class RegistratoreCompatto(private val contesto: Context) : Registratore {
    private val codificatore = Codificatore()
    private val principale = ContextCompat.getMainExecutor(contesto)

    @SuppressLint("RestrictedApi")
    override suspend fun collega(proprietario: LifecycleOwner, anteprima: PreviewView) {
        val provider = ProcessCameraProvider.awaitInstance(contesto)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(anteprima.surfaceProvider) }
        val vc = VideoCapture.Builder(UscitaCompatta(recorderDiSempre(), codificatore::superficie))
            .setTargetFrameRate(Range(FOTOGRAMMI_AL_SECONDO, FOTOGRAMMI_AL_SECONDO))
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(proprietario, fotocamera(provider), preview, vc)
    }

    override fun avvia(onEvento: (EventoRipresa) -> Unit): File {
        val file = File(cartellaVideo(contesto), "${UUID.randomUUID()}.mp4")
        codificatore.avvia(file) { e -> principale.execute { onEvento(e) } }
        return file
    }

    override fun ferma() = codificatore.ferma()

    override fun chiudi() = codificatore.chiudi()

    companion object {
        /** Obiettivo a bitrate costante: display e badge quasi fermi, 720p a 15 fotogrammi al secondo. */
        const val BITRATE_COMPATTO = 800_000

        /** Un fotogramma chiave ogni 5 s (CameraX: ogni secondo): in una ripresa di 15 s, al massimo tre. */
        const val SECONDI_TRA_CHIAVI = 5

        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        internal fun formato(
            larghezza: Int,
            altezza: Int,
            modo: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        ): MediaFormat =
            MediaFormat.createVideoFormat(MIME, larghezza, altezza).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_COMPATTO)
                setInteger(MediaFormat.KEY_BITRATE_MODE, modo)
                setInteger(MediaFormat.KEY_FRAME_RATE, FOTOGRAMMI_AL_SECONDO)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, SECONDI_TRA_CHIAVI)
            }

        /** Il codificatore H.264 che dichiara di accettare il formato (CBR compreso), o null. */
        private fun dichiaraCbr(formato: MediaFormat): String? =
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(formato) }.getOrNull()

        /**
         * Il codificatore per quella risoluzione: prima uno che dichiara il CBR; altrimenti il primo hardware, a cui il
         * CBR si chiede lo stesso. Sul POCO X6 Pro (06/11/2026) il MediaTek `c2.mtk.avc.encoder` dichiara solo il VBR, e il
         * codificatore software di Android, che dichiara il CBR, Xiaomi lo limita a circa 720x480.
         */
        internal fun scegli(larghezza: Int, altezza: Int): Scelta? {
            val avc = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                    info.isEncoder && !info.isAlias && !info.name.endsWith(".secure") &&
                        info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
                }
            }.getOrDefault(emptyList())
            dichiaraCbr(formato(larghezza, altezza))?.let { nome ->
                return Scelta(nome, hardware = avc.firstOrNull { it.name == nome }?.isHardwareAccelerated == true, cbrDichiarato = true)
            }
            val hw = avc.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly &&
                    runCatching { info.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(larghezza, altezza) == true }.getOrDefault(false)
            } ?: return null
            return Scelta(hw.name, hardware = true, cbrDichiarato = false)
        }

        /** C'è un codificatore H.264 per il 720p da usare così? Senza, si resta su CameraX. */
        fun supportato(): Boolean = scegli(1280, 720) != null

        /** Per la scheda del telefono: (breve, dettaglio) della codifica compatta, o CameraX senza codificatore adatto. */
        fun descriviScelta(): Pair<String, String> = scegli(1280, 720)?.let {
            "compatta · ${BITRATE_COMPATTO / 1000} kbit/s" to
                "Codificatore ${it.nome} (${if (it.hardware) "hardware" else "software"}), bitrate costante" +
                if (it.cbrDichiarato) "." else " (non dichiarato dal telefono, rispettato)."
        } ?: ("CameraX" to "Nessun codificatore adatto alla codifica compatta.")
    }

    /** Il codificatore scelto da [scegli]. */
    internal data class Scelta(val nome: String, val hardware: Boolean, val cbrDichiarato: Boolean)

    /** Codificatore e modalità usati davvero nell'ultima ripresa, per la schermata dopo lo stop. */
    override val descrizione: String get() = codificatore.descrizione ?: "compatta"
}

/**
 * MediaCodec H.264 in CBR con ingresso da superficie, e MediaMuxer per l'MP4. Tutto lo stato vive sul thread
 * "codifica": le chiamate da fuori ([superficie], [avvia], [ferma], [chiudi]) vi si spostano con il suo Handler.
 *
 * Il codificatore parte quando CameraX consegna la superficie e lavora finché la fotocamera è collegata; il file si
 * scrive solo fra [avvia] e [ferma], a partire da un fotogramma chiave, e si chiude da solo a 15 secondi.
 */
private class Codificatore {
    private val thread = HandlerThread("codifica").apply { start() }
    private val gestore = Handler(thread.looper)
    private val qui = Executor { gestore.post(it) }

    private class Richiesta(val file: File, val evento: (EventoRipresa) -> Unit, val avviataMs: Long = SystemClock.elapsedRealtime())

    private var codec: MediaCodec? = null
    private var superficie: Surface? = null
    private var formatoUscita: MediaFormat? = null
    private var rotazione = 0
    private var richiesta: Richiesta? = null
    private var muxer: MediaMuxer? = null
    private var traccia = -1
    private var primoPts = 0L
    private var ultimoAvviso = -1L
    private var scritti = 0

    /** Codificatore e modalità in uso ("c2.mtk.avc.encoder · CBR"), letto dal thread principale. */
    @Volatile var descrizione: String? = null
        private set

    fun superficie(request: SurfaceRequest) {
        gestore.post { prepara(request) }
    }

    fun avvia(file: File, evento: (EventoRipresa) -> Unit) {
        gestore.post {
            richiesta = Richiesta(file, evento)
            codec?.let(::chiediChiave) // già al lavoro: il prossimo fotogramma sia una chiave, da cui parte il file
        }
    }

    fun ferma() {
        gestore.post { concludi(null) }
    }

    fun chiudi() {
        gestore.post {
            concludi("fotocamera chiusa")
            rilascia(codec, superficie)
            thread.quitSafely()
        }
    }

    private fun prepara(request: SurfaceRequest) {
        rilascia(codec, superficie) // una superficie nuova sostituisce la vecchia
        val dim = request.resolution
        val scelta = RegistratoreCompatto.scegli(dim.width, dim.height)
        if (scelta == null) {
            request.willNotProvideSurface()
            concludi("nessun codificatore H.264 per ${dim.width}x${dim.height}", guasto = true)
            return
        }
        // CBR; se il codificatore lo rifiuta, VBR (resta il fotogramma chiave ogni 5 s)
        val prove = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR to "VBR (CBR rifiutato)",
        )
        var c: MediaCodec? = null
        var modo = ""
        for ((valore, nomeModo) in prove) {
            val prova = MediaCodec.createByCodecName(scelta.nome)
            try {
                prova.setCallback(Richiami(), gestore)
                prova.configure(RegistratoreCompatto.formato(dim.width, dim.height, valore), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c = prova
                modo = nomeModo
                break
            } catch (e: Exception) {
                Log.w(TAG, "${scelta.nome}: $nomeModo non accettato", e)
                runCatching { prova.release() }
            }
        }
        if (c == null) {
            request.willNotProvideSurface()
            concludi("il codificatore ${scelta.nome} non accetta il formato", guasto = true)
            return
        }
        try {
            val s = c.createInputSurface()
            c.start()
            codec = c
            superficie = s
            descrizione = "${scelta.nome} · $modo"
            Log.i(
                TAG,
                "${scelta.nome} ${dim.width}x${dim.height} $modo ${RegistratoreCompatto.BITRATE_COMPATTO} bit/s, " +
                    "chiave ogni ${RegistratoreCompatto.SECONDI_TRA_CHIAVI} s (CBR dichiarato: ${scelta.cbrDichiarato}); " +
                    "formato in uscita: ${runCatching { c.outputFormat }.getOrNull()}",
            )
            request.setTransformationInfoListener(qui) { info -> rotazione = info.rotationDegrees }
            // la fotocamera ha finito con la superficie (schermata chiusa, nuovo collegamento): si libera tutto
            request.provideSurface(s, qui) { rilascia(c, s) }
            if (richiesta != null) chiediChiave(c)
        } catch (e: Exception) {
            Log.w(TAG, "codificatore non avviato", e)
            runCatching { c.release() }
            request.willNotProvideSurface()
            concludi("codificatore non avviato: ${e.message}", guasto = true)
        }
    }

    private fun chiediChiave(c: MediaCodec) {
        runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private inner class Richiami : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, indice: Int) = Unit // ingresso dalla superficie

        override fun onOutputFormatChanged(c: MediaCodec, formato: MediaFormat) {
            if (c === codec) formatoUscita = formato
        }

        override fun onOutputBufferAvailable(c: MediaCodec, indice: Int, info: MediaCodec.BufferInfo) {
            try {
                if (c === codec) scrivi(c, indice, info)
            } catch (e: Exception) {
                Log.w(TAG, "scrittura del video non riuscita", e)
                concludi("scrittura del video non riuscita: ${e.message}", guasto = true)
            } finally {
                runCatching { c.releaseOutputBuffer(indice, false) }
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "errore del codificatore", e)
            if (c === codec) {
                concludi("errore del codificatore: ${e.diagnosticInfo}", guasto = true)
                rilascia(c, superficie)
            }
        }
    }

    private fun scrivi(c: MediaCodec, indice: Int, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0) return // SPS e PPS: nel formato
        val r = richiesta ?: return // nessuna ripresa in corso: il fotogramma si scarta
        var m = muxer
        if (m == null) {
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return // il file comincia da un fotogramma chiave
            val formato = formatoUscita ?: return
            m = MediaMuxer(r.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            m.setOrientationHint(rotazione)
            traccia = m.addTrack(formato)
            m.start()
            muxer = m
            primoPts = info.presentationTimeUs
        }
        val pts = info.presentationTimeUs - primoPts
        if (pts < 0) return
        if (pts >= Registratore.DURATA_MASSIMA_MS * 1000) {
            concludi(null) // limite dei 15 secondi: finita bene, come con CameraX
            return
        }
        val dati = c.getOutputBuffer(indice) ?: return
        m.writeSampleData(traccia, dati, MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) })
        scritti++
        if (ultimoAvviso < 0 || pts - ultimoAvviso >= 250_000) {
            ultimoAvviso = pts
            r.evento(EventoRipresa.Durata(pts / 1000))
        }
    }

    /**
     * Chiude la ripresa in corso: con [errore] null è riuscita se c'è almeno un fotogramma nel file. [guasto]: colpa del
     * codificatore (si passa a CameraX); lo è anche una ripresa fermata dopo 3 s senza nessun fotogramma.
     */
    private fun concludi(errore: String?, guasto: Boolean = false) {
        val r = richiesta ?: return
        richiesta = null
        val m = muxer
        muxer = null
        val chiuso = m != null && scritti > 0 && runCatching { m.stop() }.isSuccess
        m?.let { runCatching { it.release() } }
        scritti = 0
        ultimoAvviso = -1L
        val muto = errore == null && !chiuso && SystemClock.elapsedRealtime() - r.avviataMs >= 3_000
        r.evento(
            when {
                errore == null && chiuso -> EventoRipresa.Fine(true)
                errore == null -> EventoRipresa.Fine(false, "nessun fotogramma registrato", guastoCodifica = muto)
                else -> EventoRipresa.Fine(false, errore, guastoCodifica = guasto)
            },
        )
    }

    private fun rilascia(c: MediaCodec?, s: Surface?) {
        if (c == null) return
        if (c === codec) {
            concludi("la fotocamera ha smesso di mandare fotogrammi")
            codec = null
            superficie = null
            formatoUscita = null
        }
        runCatching { c.stop() }
        runCatching { c.release() }
        s?.release()
    }
}

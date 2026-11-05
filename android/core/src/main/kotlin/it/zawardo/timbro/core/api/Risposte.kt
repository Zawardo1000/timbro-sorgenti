package it.zawardo.timbro.core.api

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Risposte dell'API per l'app (`/api/v1`), con i nomi dei campi del backend. Esempi condivisi con i test del
 * backend in `tests/fixtures/contratto/risposte.json`: se il server cambia, `tests/test_contratto.py` fallisce e
 * questi modelli vanno aggiornati insieme agli esempi. Campi sconosciuti ignorati: un server più nuovo non rompe
 * un'app più vecchia.
 */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** `POST /devices/register` */
@Serializable
data class Registrazione(
    @SerialName("device_id") val deviceId: String,
    @SerialName("api_token") val apiToken: String,
)

/** `POST /punches` e `GET /punches/{id}`: stato di una timbratura di questo telefono. */
@Serializable
data class StatoTimbratura(
    val id: Long,
    @SerialName("client_id") val clientId: String,
    val kind: String,
    val sha256: String,
    @SerialName("server_received_at") val ricevutaIl: String,
    @SerialName("chain_hash") val chainHash: String,
    @SerialName("video_received") val videoRicevuto: Boolean,
    val stamps: List<Marca> = emptyList(),
    val location: PosizioneRegistrata? = null,
    val attestation: EsitoAttestazione? = null,
) {
    /** La spunta "marcata" dell'app: almeno una marca qualificata completata sul video. */
    val marcataQualificata: Boolean get() = stamps.any { it.target == "video" && it.qualified && it.status == "complete" }

    /** La prova OpenTimestamps del video è confermata su Bitcoin. */
    val confermataSuBitcoin: Boolean get() = stamps.any { it.target == "video" && it.provider == "ots" && it.status == "complete" }
}

@Serializable
data class Marca(
    val target: String,
    val provider: String,
    val qualified: Boolean,
    val status: String,
    @SerialName("gen_time") val ora: String? = null,
)

@Serializable
data class PosizioneRegistrata(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val precisioneM: Double? = null,
    val provider: String? = null,
    @SerialName("is_mock") val simulata: Boolean? = null,
    @SerialName("fix_time") val oraFix: String? = null,
)

@Serializable
data class EsitoAttestazione(val ok: Boolean, val problems: List<String> = emptyList())

/**
 * `POST /punches`: i **byte esatti** del manifest e la firma, in base64, più l'eventuale catena di attestazione della
 * chiave usa e getta (DER base64, dalla foglia alla radice; docs/PROGETTO.md §19). Idempotente sul client_id.
 */
@Serializable
data class RichiestaTimbratura(
    val manifest: String,
    val signature: String,
    val attestation: List<String>? = null,
)

/** `GET /history?until&days`: giorni completi in ora italiana, dal più recente. */
@Serializable
data class Storico(
    val days: List<GiornoStorico>,
    @SerialName("next_until") val nextUntil: String? = null,
) {
    companion object {
        const val MAX_GIORNI = 62

        /** Query della richiesta: fino al giorno [fino] (compreso, null = oggi) per [giorni] giorni indietro. */
        fun query(fino: LocalDate?, giorni: Int = 14): String {
            val g = giorni.coerceIn(1, MAX_GIORNI)
            return if (fino == null) "days=$g" else "until=$fino&days=$g"
        }
    }
}

@Serializable
data class GiornoStorico(val date: String, val punches: List<VoceStorico>) {
    val giorno: LocalDate get() = LocalDate.parse(date)
}

@Serializable
data class VoceStorico(
    val id: Long,
    @SerialName("client_id") val clientId: String,
    val kind: String,
    /** Ora della timbratura (quella del telefono se plausibile), ISO 8601 con fuso. */
    val time: String,
    @SerialName("server_received_at") val ricevutaIl: String,
    /** "ricevuto", "mancante" o "cancellato" */
    val video: String,
    @SerialName("qualified_stamp") val marcataQualificata: Boolean,
    @SerialName("attestation_ok") val attestazioneOk: Boolean? = null,
    val contested: Boolean,
    val keep: Boolean,
)

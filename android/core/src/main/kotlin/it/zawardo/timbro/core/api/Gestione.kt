package it.zawardo.timbro.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Gestione delle proprie timbrature (`timbro/api/admin_api.py`): dettaglio con i dati sensibili, flag, video, link di
 * condivisione, impostazioni. Tutte le chiamate sono firmate con la chiave admin (impronta).
 *
 * Il dettaglio (`GET /admin/punches/{id}`) porta posizione, reti Wi-Fi, note e l'attestazione completa: per questo
 * chiede l'impronta, mentre lo storico (`/history`) e lo stato delle prove (`/punches/{id}`) no.
 */

/** `GET /admin/punches/{id}` (e risposta di `PATCH` e `DELETE .../video`, senza i campi del solo dettaglio). */
@Serializable
data class DettaglioTimbratura(
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
    val keep: Boolean = false,
    val contested: Boolean = false,
    @SerialName("justification_ref") val giustificativo: String? = null,
    val notes: String? = null,
    @SerialName("device_time") val oraTelefono: String? = null,
    @SerialName("video_available") val videoDisponibile: Boolean = false,
    @SerialName("video_deleted_at") val videoCancellatoIl: String? = null,
    @SerialName("video_deleted_reason") val motivoCancellazione: String? = null,
    /** Solo nel dettaglio: l'esito completo dell'attestazione hardware. */
    @SerialName("attestation_detail") val attestazione: DettaglioAttestazione? = null,
    /** Solo nel dettaglio: le reti Wi-Fi e quante altre timbrature le hanno viste (null se il manifest non ne ha). */
    val wifi: RiepilogoWifi? = null,
    /** Solo nel dettaglio: le mail di contestazione mandate per questa timbratura. */
    val notices: List<AvvisoMail> = emptyList(),
)

/** Esito completo dell'attestazione (`timbro/attestation.py`, `Result`). */
@Serializable
data class DettaglioAttestazione(
    val ok: Boolean = false,
    val problems: List<String> = emptyList(),
    @SerialName("security_level") val livello: String? = null,
    @SerialName("device_locked") val bloccato: Boolean? = null,
    @SerialName("verified_boot_state") val avvio: String? = null,
    @SerialName("os_patch_level") val patch: Int? = null,
    /** [nome del pacchetto, versionCode] per ogni app che condivide l'utente Linux (di solito una). */
    val packages: List<JsonArray> = emptyList(),
    val provisioning: String? = null,
    val revoked: List<String> = emptyList(),
) {
    /** "it.zawardo.timbro (versione 29)" per ogni pacchetto attestato. */
    val app: List<String>
        get() = packages.mapNotNull { p ->
            val nome = (p.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val versione = (p.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            if (versione != null) "$nome (versione $versione)" else nome
        }

    /** "TEE", "StrongBox", "software" come lo scrive il server. */
    val livelloLeggibile: String?
        get() = when (livello) {
            "tee" -> "TEE"
            "strongbox" -> "StrongBox"
            null -> null
            else -> livello
        }
}

/** `services.wifi_summary`: le reti della sede si ripetono giorno dopo giorno. */
@Serializable
data class RiepilogoWifi(
    val reti: Int = 0,
    @SerialName("gia_viste_in_altre_timbrature") val giaViste: Int = 0,
    @SerialName("scartate_mac_casuali") val scartate: Int = 0,
    val dettaglio: List<ReteVistaServer> = emptyList(),
)

@Serializable
data class ReteVistaServer(
    val bssid: String,
    val rssi: Int? = null,
    val mhz: Int? = null,
    @SerialName("viste_in_altre_timbrature") val visteAltrove: Int = 0,
)

/**
 * `PATCH /admin/punches/{id}`: solo i campi presenti cambiano (null = non mandato). Per togliere giustificativo o note
 * si manda la stringa vuota.
 */
@Serializable
data class ModificaTimbratura(
    val keep: Boolean? = null,
    val contested: Boolean? = null,
    @SerialName("justification_ref") val giustificativo: String? = null,
    val notes: String? = null,
)

/** `POST /admin/punches/{id}/video-ticket`: URL relativo (`/api/v1/media/<ticket>`) valido pochi minuti. */
@Serializable
data class TicketVideo(val url: String, @SerialName("expires_in") val durataS: Int = 300)

/** `POST /admin/shares` */
@Serializable
data class RichiestaLink(
    @SerialName("punch_ids") val timbrature: List<Long>,
    val label: String,
    val days: Int? = null,
    @SerialName("allow_evidence_download") val download: Boolean = true,
)

/** Una riga di `GET /admin/shares/{id}/accesses`, dalla più vecchia. */
@Serializable
data class AccessoLink(
    val id: Long,
    val at: String,
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    /** "page", "video:<id>", "evidence:<id>" */
    val what: String,
) {
    val descrizione: String
        get() = when {
            what == "page" -> "pagina"
            what.startsWith("video:") -> "video della timbratura ${what.removePrefix("video:")}"
            what.startsWith("evidence:") -> "pacchetto di prova della timbratura ${what.removePrefix("evidence:")}"
            else -> what
        }
}

/** `GET /admin/settings`: la conservazione la cambia solo l'amministratore (`PATCH /admin/system/settings`). */
@Serializable
data class Impostazioni(
    @SerialName("retention_days") val conservazioneGiorni: Int,
    @SerialName("share_default_days") val linkGiorniPredefiniti: Int = 15,
    @SerialName("share_max_days") val linkGiorniMax: Int = 60,
)

/** `PATCH /admin/system/settings` (e la sua risposta). */
@Serializable
data class Conservazione(@SerialName("retention_days") val giorni: Int) {
    companion object {
        const val MIN = 7
        const val MAX = 3650
    }
}

/** Le regole della gestione condivise con il server. */
object Gestione {
    const val MAX_GIUSTIFICATIVO = 255
    const val MAX_NOTE = 4000
    const val MAX_ETICHETTA = 200

    /** Il video si può cancellare: c'è, è arrivato, e non è da tenere né contestato (altrimenti il server dice 409). */
    fun videoCancellabile(d: DettaglioTimbratura): Boolean =
        d.videoDisponibile && d.videoCancellatoIl == null && !d.keep && !d.contested
}

/**
 * Lo storico del server e la coda del telefono insieme: una timbratura della coda conta finché il server non la
 * restituisce nello storico (stesso `client_id`); da lì vale quella del server.
 */
object Unione {
    fun <S, C> soloInCoda(server: Collection<S>, coda: List<C>, chiaveServer: (S) -> String, chiaveCoda: (C) -> String): List<C> {
        val note = server.mapTo(HashSet(), chiaveServer)
        return coda.filter { chiaveCoda(it) !in note }
    }
}

/** Ora della timbratura nel fuso [zona] (quella del telefono se plausibile, come la sceglie il server). */
fun VoceStorico.quando(zona: java.time.ZoneId): java.time.LocalDateTime =
    java.time.OffsetDateTime.parse(time).atZoneSameInstant(zona).toLocalDateTime()

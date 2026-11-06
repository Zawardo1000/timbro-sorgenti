package it.zawardo.timbro.core.api

import it.zawardo.timbro.core.Nota
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Contestazione di una timbratura o segnalazione di tutta una giornata: profilo (nome, matricola, destinatari),
 * richiesta al server ed esito; il testo della mail è in [TestoMail]. Contratto con il backend:
 * `GET/PUT /api/v1/admin/profile`, `POST /api/v1/admin/punches/{id}/contest` e `POST /api/v1/admin/days/{data}/contest`
 * (esempi in `tests/fixtures/contratto/risposte.json`, chiavi `profile` e `contest`).
 */

/** `GET /admin/profile` (e risposta di `PUT`). */
@Serializable
data class Profilo(
    @SerialName("full_name") val nome: String? = null,
    @SerialName("employee_number") val matricola: String? = null,
    @SerialName("contest_recipients") val destinatari: List<String> = emptyList(),
    /** Il server ha l'SMTP configurato: senza, una contestazione crea solo il link. */
    @SerialName("mail_configured") val mailConfigurata: Boolean = false,
    /**
     * Indirizzo personale, facoltativo: le mail partono dalla casella del server con Reply-To verso questo indirizzo,
     * che riceve anche la copia (Cc).
     */
    val email: String? = null,
) {
    /** Nome e matricola ci sono: servono alla mail (senza, il server risponde 409). */
    val completo: Boolean get() = !nome.isNullOrBlank() && !matricola.isNullOrBlank()
}

/**
 * Corpo di `PUT /admin/profile` (e, per l'amministratore, di `PUT /admin/system/users/{id}/profile`). È una
 * sostituzione: un [email] null (non mandato) toglie l'indirizzo personale.
 */
@Serializable
data class ModificaProfilo(
    @SerialName("full_name") val nome: String,
    @SerialName("employee_number") val matricola: String,
    @SerialName("contest_recipients") val destinatari: List<String>,
    val email: String? = null,
)

/**
 * Corpo di `POST /admin/punches/{id}/contest`. null = valore predefinito del server: 15 giorni, destinatari del
 * profilo. `recipients = []` crea solo il link, senza mail.
 */
@Serializable
data class RichiestaContestazione(
    val days: Int? = null,
    val recipients: List<String>? = null,
    val note: String? = null,
)

/**
 * Esito di `POST /admin/punches/{id}/contest` (punch presente) e di `POST /admin/days/{data}/contest` (tutta la
 * giornata: punch null). [hours] è il calcolo delle ore del server con l'orario dell'utente (null senza orario).
 */
@Serializable
data class EsitoContestazione(
    val day: String,
    val punch: RiepilogoAdmin? = null,
    val share: LinkCondivisione,
    val notice: AvvisoMail? = null,
    val hours: OreServer? = null,
) {
    val giorno: LocalDate get() = LocalDate.parse(day)
}

/** Ore valide calcolate dal server (timbro/orario.py): stesse regole e stessi codici di [it.zawardo.timbro.core.Ore]. */
@Serializable
data class OreServer(
    @SerialName("worked_min") val lavoratiMin: Int,
    @SerialName("due_min") val dovutiMin: Int? = null,
    @SerialName("balance_min") val saldoMin: Int? = null,
    val complete: Boolean = false,
    val open: Boolean = false,
    val adjustments: List<NotaServer> = emptyList(),
    val anomalies: List<NotaServer> = emptyList(),
)

/** Correzione o anomalia del calcolo: codice e parametri (numeri o testi "HH:MM"). */
@Serializable
data class NotaServer(val code: String, val params: Map<String, JsonPrimitive> = emptyMap()) {
    fun nota(): Nota = Nota(code, params.mapValues { (_, v) -> if (v.isString) v.content else v.int })
}

/** Riepilogo di una timbratura nell'API admin (`GET /admin/punches/{id}`, e `punch` dell'esito). */
@Serializable
data class RiepilogoAdmin(
    val id: Long,
    @SerialName("client_id") val clientId: String,
    val kind: String,
    val sha256: String,
    @SerialName("server_received_at") val ricevutaIl: String,
    @SerialName("video_received") val videoRicevuto: Boolean,
    val stamps: List<Marca> = emptyList(),
    val contested: Boolean = false,
    val keep: Boolean = false,
    @SerialName("justification_ref") val giustificativo: String? = null,
    val notes: String? = null,
    @SerialName("device_time") val oraTelefono: String? = null,
    @SerialName("video_available") val videoDisponibile: Boolean = false,
    /** Solo nel dettaglio (`GET /admin/punches/{id}`): le mail di contestazione mandate per questa timbratura. */
    val notices: List<AvvisoMail> = emptyList(),
)

/** Link di condivisione (`POST /admin/shares` e `share` dell'esito): `url` col token solo alla creazione. */
@Serializable
data class LinkCondivisione(
    val id: Long,
    val label: String,
    @SerialName("punch_ids") val punchIds: List<Long>,
    @SerialName("allow_evidence_download") val download: Boolean,
    @SerialName("created_at") val creatoIl: String,
    @SerialName("expires_at") val scade: String,
    @SerialName("revoked_at") val revocatoIl: String? = null,
    val active: Boolean = true,
    val accesses: Int = 0,
    val url: String? = null,
    /** Giornate coperte (ISO, ora italiana): nell'elenco un link si riconosce dal giorno. Vuoto con un server vecchio. */
    val days: List<String> = emptyList(),
)

/** Mail di contestazione: la manda il worker del server, ritentando gli errori temporanei. */
@Serializable
data class AvvisoMail(
    val id: Long,
    val status: String,
    val recipients: List<String>,
    val subject: String,
    @SerialName("created_at") val creatoIl: String,
    @SerialName("sent_at") val inviatoIl: String? = null,
    val error: String? = null,
    @SerialName("share_id") val shareId: Long? = null,
    /** Giorno segnalato. */
    val day: String? = null,
    /** La timbratura contestata; null se la segnalazione è di tutta la giornata. */
    @SerialName("punch_id") val punchId: Long? = null,
) {
    val stato: StatoAvviso get() = StatoAvviso.da(status)
}

enum class StatoAvviso(val valore: String) {
    IN_INVIO("pending"), INVIATA("sent"), RITENTA("retrying"), FALLITA("failed");

    companion object {
        /** Uno stato nuovo del server vale "in invio": l'app vecchia non si rompe. */
        fun da(valore: String): StatoAvviso = entries.firstOrNull { it.valore == valore } ?: IN_INVIO
    }
}

/** Regole condivise con il server: limiti dei campi e controllo (semplice e prevedibile) degli indirizzi. */
object Contestazioni {
    const val MAX_DESTINATARI = 10
    const val MAX_NOME = 100
    const val MAX_MATRICOLA = 40
    const val MAX_NOTA = 1000
    const val MAX_EMAIL = 254
    const val GIORNI_PREDEFINITI = 15
    const val GIORNI_MAX = 60
    val DURATE = listOf(7, 15, 30, 60)

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun emailValida(indirizzo: String): Boolean = indirizzo.length <= MAX_EMAIL && EMAIL.matches(indirizzo)

    /**
     * Problemi del profilo da mostrare all'utente; vuota = si può salvare. Gli indirizzi vanno già senza spazi; [email]
     * (personale, facoltativo) si controlla come gli altri.
     */
    fun problemiProfilo(nome: String, matricola: String, destinatari: List<String>, email: String? = null): List<String> = buildList {
        if (nome.length > MAX_NOME) add("Nome troppo lungo (al massimo $MAX_NOME caratteri)")
        if (matricola.length > MAX_MATRICOLA) add("Matricola troppo lunga (al massimo $MAX_MATRICOLA caratteri)")
        if ('\u0000' in nome || '\u0000' in matricola) add("Carattere non ammesso")
        if (destinatari.size > MAX_DESTINATARI) add("Al massimo $MAX_DESTINATARI destinatari")
        destinatari.filterNot(::emailValida).forEach { add("Indirizzo non valido: $it") }
        destinatari.groupBy { it.lowercase(Locale.ROOT) }.filterValues { it.size > 1 }.keys.forEach { add("Indirizzo ripetuto: $it") }
        if (!email.isNullOrEmpty() && !emailValida(email)) add("Il tuo indirizzo email non è valido")
    }

    /** Problemi di una richiesta di contestazione; vuota = si può mandare. */
    fun problemiRichiesta(giorni: Int, destinatari: List<String>, nota: String?): List<String> = buildList {
        if (giorni !in 1..GIORNI_MAX) add("Validità del link fra 1 e $GIORNI_MAX giorni")
        if (destinatari.size > MAX_DESTINATARI) add("Al massimo $MAX_DESTINATARI destinatari")
        destinatari.filterNot(::emailValida).forEach { add("Indirizzo non valido: $it") }
        if ((nota?.length ?: 0) > MAX_NOTA) add("Nota troppo lunga (al massimo $MAX_NOTA caratteri)")
    }
}


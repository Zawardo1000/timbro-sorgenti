package it.zawardo.timbro.core.api

import it.zawardo.timbro.core.DataSerializer
import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.VersioneOrario
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Chi è l'utente del telefono e amministrazione di sistema (`timbro/api/system_api.py`, `timbro/accounts.py`).
 *
 * Due livelli, sempre con la firma della chiave admin (impronta):
 * - l'utente gestisce solo le proprie timbrature, i link, le contestazioni e il proprio profilo; il suo orario lo vede;
 * - l'amministratore (ruolo `admin`) gestisce utenti, telefoni, articolazioni orarie e assegnazioni:
 *   `/api/v1/admin/system/...`.
 *
 * Le articolazioni hanno un nome e versioni datate ([VersioneOrario]) e sono condivisibili; a un profilo si assegnano
 * dal giorno X. All'app l'orario arriva già risolto (`GET /api/v1/schedule`, un solo elenco di versioni): [risolvi]
 * fa la stessa cosa di `accounts.resolved_schedules`, per il prototipo e per i test.
 * Esempi in `tests/fixtures/contratto/risposte.json`: `me`, `system_users`, `system_schedules`, `pairing_code`.
 */

/**
 * Nome, matricola, indirizzo personale e destinatari, come stanno nel profilo (senza `mail_configured`, che è del
 * server).
 */
@Serializable
data class DatiProfilo(
    @SerialName("full_name") val nome: String? = null,
    @SerialName("employee_number") val matricola: String? = null,
    @SerialName("contest_recipients") val destinatari: List<String> = emptyList(),
    val email: String? = null,
)

/** Articolazione assegnata a un profilo dal giorno [validoDal] (fino all'assegnazione successiva). */
@Serializable
data class Assegnazione(
    @SerialName("schedule_id") val articolazioneId: Long,
    @SerialName("schedule_name") val nomeArticolazione: String? = null,
    @SerialName("valid_from") @Serializable(with = DataSerializer::class) val validoDal: LocalDate,
)

/** `GET /api/v1/me`: l'app mostra l'amministrazione solo se [admin]. */
@Serializable
data class Io(
    @SerialName("user_id") val utenteId: Long,
    val username: String,
    val role: String,
    @SerialName("device_id") val telefonoId: String,
    val profile: DatiProfilo = DatiProfilo(),
    val assignments: List<Assegnazione> = emptyList(),
    /** Il server può mandare le mail di contestazione (SMTP configurato); null da un server che non lo dice. */
    @SerialName("mail_configured") val mailConfigurata: Boolean? = null,
) {
    val admin: Boolean get() = role == Amministrazione.ADMIN
}

@Serializable
data class TelefonoUtente(
    val id: String,
    val name: String,
    @SerialName("created_at") val associatoIl: String,
    @SerialName("revoked_at") val revocatoIl: String? = null,
) {
    val attivo: Boolean get() = revocatoIl == null
}

/** Un utente nell'elenco dell'amministratore (`GET /admin/system/users`). */
@Serializable
data class UtenteSistema(
    val id: Long,
    val username: String,
    val role: String,
    val profile: DatiProfilo = DatiProfilo(),
    val assignments: List<Assegnazione> = emptyList(),
    val devices: List<TelefonoUtente> = emptyList(),
) {
    val admin: Boolean get() = role == Amministrazione.ADMIN

    /** L'assegnazione in vigore quel giorno (l'ultima iniziata). */
    fun assegnazione(giorno: LocalDate): Assegnazione? = assignments.filter { !it.validoDal.isAfter(giorno) }.maxByOrNull { it.validoDal }
}

/** Articolazione oraria con un nome e versioni datate (`GET /admin/system/schedules`). */
@Serializable
data class Articolazione(
    val id: Long,
    val name: String,
    val versions: List<VersioneOrario> = emptyList(),
) {
    fun inVigore(giorno: LocalDate): VersioneOrario? = Orario(versions).inVigore(giorno)
}

/** `POST /admin/system/users/{id}/pairing-code`: il codice in chiaro esiste solo in questa risposta. */
@Serializable
data class CodiceAssociazione(
    val code: String,
    @SerialName("valid_minutes") val minuti: Int,
    val username: String,
)

// --- richieste dell'amministratore ------------------------------------------------------------------------------

/** `POST /admin/system/users` */
@Serializable
data class NuovoUtente(
    val username: String,
    val role: String = Amministrazione.UTENTE,
    @SerialName("full_name") val nome: String? = null,
    @SerialName("employee_number") val matricola: String? = null,
)

/** `PUT /admin/system/users/{id}/role` */
@Serializable
data class CambioRuolo(val role: String)

/** `POST /admin/system/schedules`: nome e prima versione. */
@Serializable
data class NuovaArticolazione(val name: String, val version: VersioneOrario)

/** `PUT /admin/system/schedules/{id}/name` */
@Serializable
data class NuovoNome(val name: String)

/** Un elemento di `PUT /admin/system/users/{id}/assignments` (il corpo è l'elenco). */
@Serializable
data class RichiestaAssegnazione(
    @SerialName("schedule_id") val articolazioneId: Long,
    @SerialName("valid_from") @Serializable(with = DataSerializer::class) val validoDal: LocalDate,
)

/** Regole dell'amministrazione condivise con il server, e la risoluzione dell'orario. */
object Amministrazione {
    const val UTENTE = "user"
    const val ADMIN = "admin"
    val RUOLI = listOf(UTENTE, ADMIN)
    const val MAX_NOME_ARTICOLAZIONE = 100

    private val NOME_UTENTE = Regex("^[a-z0-9._-]{1,64}$")

    /**
     * JSON delle richieste: sempre tutti i campi delle versioni (anche quelli col valore predefinito), mai i null.
     * Le versioni contengono date e orari nei formati del backend ("2026-01-01", "07:30:00").
     */
    val JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun nomeUtenteValido(nome: String): Boolean = NOME_UTENTE.matches(nome)

    fun problemiNuovoUtente(username: String, nome: String, matricola: String, esistenti: Collection<String>): List<String> = buildList {
        if (!nomeUtenteValido(username)) add("Nome utente: lettere minuscole, cifre, punto, trattino o trattino basso")
        if (username in esistenti) add("Nome utente già usato")
        addAll(Contestazioni.problemiProfilo(nome, matricola, emptyList()))
    }

    fun problemiNomeArticolazione(nome: String, esistenti: Collection<String>): List<String> = buildList {
        val n = nome.trim()
        if (n.isEmpty()) add("Serve un nome")
        if (n.length > MAX_NOME_ARTICOLAZIONE) add("Nome troppo lungo (al massimo $MAX_NOME_ARTICOLAZIONE caratteri)")
        if (esistenti.any { it.equals(n, ignoreCase = true) }) add("Esiste già un'articolazione con questo nome")
    }

    fun problemiAssegnazioni(assegnazioni: List<RichiestaAssegnazione>): List<String> = buildList {
        if (assegnazioni.map { it.validoDal }.toSet().size != assegnazioni.size) add("Due assegnazioni con la stessa data")
    }

    /** Togliere il ruolo all'ultimo amministratore lo lascerebbe senza: il server risponde 422. */
    fun ultimoAdmin(utenti: List<UtenteSistema>, id: Long): Boolean = utenti.count { it.admin } <= 1 && utenti.any { it.id == id && it.admin }

    /**
     * Orario risolto di un profilo, giorno per giorno: per ogni assegnazione (dal suo inizio alla successiva) la versione
     * dell'articolazione in vigore all'inizio e quelle che cominciano dopo. Come `accounts.resolved_schedules`.
     */
    fun risolvi(assegnazioni: List<Assegnazione>, articolazioni: List<Articolazione>): Orario {
        val perId = articolazioni.associateBy { it.id }
        val ordinate = assegnazioni.sortedBy { it.validoDal }
        val fuori = mutableListOf<VersioneOrario>()
        ordinate.forEachIndexed { i, a ->
            val fine = ordinate.getOrNull(i + 1)?.validoDal
            val versioni = perId[a.articolazioneId]?.versions.orEmpty().sortedBy { it.validoDal }
            versioni.lastOrNull { !it.validoDal.isAfter(a.validoDal) }?.let { fuori += it.copy(validoDal = a.validoDal) }
            fuori += versioni.filter { it.validoDal.isAfter(a.validoDal) && (fine == null || it.validoDal.isBefore(fine)) }
        }
        return Orario(fuori)
    }
}

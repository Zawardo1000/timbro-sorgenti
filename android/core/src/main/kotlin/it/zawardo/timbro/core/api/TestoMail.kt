package it.zawardo.timbro.core.api

import it.zawardo.timbro.core.EsitoOre
import it.zawardo.timbro.core.Ore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Oggetto e corpo (testo semplice, a capo `\n`) della mail di contestazione. */
data class Mail(val oggetto: String, val corpo: String)

/**
 * Testo standard della mail di contestazione: lo stesso dell'anteprima nell'app e della mail che manda il server
 * (`timbro/mailer.py`, `compose`). `tests/fixtures/contratto/mail.json` lo fissa carattere per carattere per tutti e
 * due (qui `TestoMailTest`). Neutro rispetto al genere ("<Nome>, matricola <N>, segnala").
 *
 * Due varianti: una timbratura contestata ("… in particolare per l'uscita delle 12:31.") o tutta la giornata (senza
 * quella frase). In entrambe l'elenco delle timbrature del giorno e, se c'è un orario, le ore valide con il saldo.
 */
object TestoMail {
    private val IT = Locale.ITALIAN
    private val DATA_BREVE = DateTimeFormatter.ofPattern("dd/MM/yyyy", IT)
    private val GIORNO_ESTESO = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", IT)
    private val ORA = DateTimeFormatter.ofPattern("HH:mm", IT)

    fun oggetto(nome: String, matricola: String, giorno: LocalDate): String =
        "Timbrature del ${giorno.format(DATA_BREVE)}: segnalazione di disallineamento - $nome, matricola $matricola"

    /** "l'uscita delle 12:31", "l'entrata delle 07:58" */
    fun timbratura(kind: String, ora: LocalTime): String =
        (if (kind == "in") "l'entrata delle " else "l'uscita delle ") + ora.format(ORA)

    /** [contestata] = (tipo, ora) della timbratura contestata; null per tutta la giornata. */
    fun primoParagrafo(nome: String, matricola: String, giorno: LocalDate, contestata: Pair<String, LocalTime>?): String {
        var testo = "$nome, matricola $matricola, segnala un disallineamento tra i dati di presenza registrati dall'ente " +
            "per ${giorno.format(GIORNO_ESTESO)} e quanto effettivamente avvenuto"
        if (contestata != null) testo += ", in particolare per ${timbratura(contestata.first, contestata.second)}"
        return "$testo."
    }

    /** "Le timbrature della giornata sono: entrata 07:58, uscita 12:31. Secondo l'articolazione oraria …" */
    fun paragrafoGiornata(timbrature: List<Pair<String, LocalTime>>, ore: EsitoOre?): String {
        val elenco = timbrature.joinToString(", ") { (k, t) -> "${if (k == "in") "entrata" else "uscita"} ${t.format(ORA)}" }
        var testo = "Le timbrature della giornata sono: $elenco."
        if (ore != null) {
            testo += " Secondo l'articolazione oraria in vigore risultano ${Ore.durata(ore.lavoratiMin)} di lavoro valido, " +
                "a fronte di ${Ore.durata(ore.dovutiMin ?: 0)} dovute"
            testo += ore.saldoMin?.let { " (saldo ${Ore.saldo(it)})." } ?: "."
        }
        return testo
    }

    /**
     * [timbrature] = tutte quelle del giorno, in ordine; [ore] = esito del calcolo con l'orario in vigore (null se
     * l'utente non ha un orario: niente ore nella mail).
     */
    fun mail(
        nome: String,
        matricola: String,
        giorno: LocalDate,
        contestata: Pair<String, LocalTime>?,
        timbrature: List<Pair<String, LocalTime>>,
        ore: EsitoOre?,
        url: String,
        scadenza: LocalDate,
        nota: String? = null,
    ): Mail {
        val contenuti = listOf("i video", "le firme", "le marche temporali") + if (ore != null) listOf("il calcolo delle ore") else emptyList()
        val paragrafi = buildList {
            add("Gentile ufficio,")
            add(primoParagrafo(nome, matricola, giorno, contestata))
            add(paragrafoGiornata(timbrature, ore))
            add(
                "Ogni timbratura della giornata è documentata da una ripresa del terminale di rilevazione, firmata dal " +
                    "telefono e marcata temporalmente da prestatori qualificati eIDAS. Al link seguente, valido fino al " +
                    "${scadenza.format(DATA_BREVE)}, si trovano ${contenuti.joinToString(", ")} e le istruzioni per la " +
                    "verifica indipendente:",
            )
            add(url)
            nota?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Nota: $it") }
            add("Si chiede di verificare e regolarizzare la posizione.")
            add("Cordiali saluti,\n$nome (matricola $matricola)")
        }
        return Mail(oggetto(nome, matricola, giorno), paragrafi.joinToString("\n\n") + "\n")
    }
}

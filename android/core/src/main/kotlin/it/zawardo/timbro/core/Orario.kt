package it.zawardo.timbro.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Articolazione dell'orario dell'utente e calcolo delle ore valide di una giornata: lo stesso di `timbro/orario.py`
 * nel backend, riga per riga. I casi di `tests/fixtures/contratto/orario.json` li controllano tutti e due (qui
 * `OrarioTest`), così l'app e la pagina per l'ente danno sempre lo stesso numero.
 *
 * L'orario ha versioni datate ("valido dal"): quando l'ente cambia articolazione se ne aggiunge una e i giorni passati
 * restano calcolati con quella di allora. Sul server le articolazioni hanno un nome, sono condivise e si assegnano ai
 * profili dal giorno X (le gestisce l'amministratore, `api/Amministrazione.kt`); all'app arriva l'orario già risolto,
 * `GET /api/v1/schedule` (senza impronta). I nomi dei campi JSON sono quelli del backend.
 *
 * Regole (tutte parametri):
 * - l'entrata prima di [VersioneOrario.ingressoDa] vale da lì; dopo [VersioneOrario.ingressoA] vale l'ora vera, ma è
 *   un'anomalia;
 * - è pausa la prima uscita fra [VersioneOrario.pausaDa] e [VersioneOrario.pausaA] seguita da un rientro, nei giorni
 *   con la pausa: conta almeno [VersioneOrario.pausaConteggiataMin] (sotto [VersioneOrario.rientroMinimoMin] la
 *   timbratrice non accetterebbe il rientro: anomalia), oltre conta la durata vera; le altre uscite contano per il
 *   tempo effettivo;
 * - senza pausa e oltre [VersioneOrario.sogliaSenzaPausaMin] di lavoro si tolgono [VersioneOrario.trattenutaSenzaPausaMin];
 * - saldo = ore valide - dovute del giorno (0 nei giorni senza orario), solo per giornate complete.
 * I minuti si contano su ore e minuti delle timbrature (i secondi non contano), come sul terminale.
 */

/** "2026-01-01" */
object DataSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("Data", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/** "07:30:00", come lo scrive il backend (Python); legge anche "07:30". */
object OraSerializer : KSerializer<LocalTime> {
    private val FORMATO = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    override val descriptor = PrimitiveSerialDescriptor("Ora", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.withNano(0).format(FORMATO))
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

/** Un giorno della settimana nell'orario: minuti dovuti e se ha la pausa (con il rientro). */
@Serializable
data class RegolaGiorno(
    @SerialName("due_min") val dovutiMin: Int,
    val pause: Boolean = true,
)

@Serializable
data class VersioneOrario(
    @SerialName("valid_from") @Serializable(with = DataSerializer::class) val validoDal: LocalDate,
    /** 1 = lunedì … 7 = domenica (le chiavi JSON sono "1".."7"); un giorno assente vale 0 dovute, senza pausa. */
    val days: Map<Int, RegolaGiorno>,
    @SerialName("entry_from") @Serializable(with = OraSerializer::class) val ingressoDa: LocalTime = LocalTime.of(7, 30),
    @SerialName("entry_to") @Serializable(with = OraSerializer::class) val ingressoA: LocalTime = LocalTime.of(9, 30),
    @SerialName("pause_from") @Serializable(with = OraSerializer::class) val pausaDa: LocalTime = LocalTime.of(12, 15),
    @SerialName("pause_to") @Serializable(with = OraSerializer::class) val pausaA: LocalTime = LocalTime.of(13, 15),
    @SerialName("pause_counted_min") val pausaConteggiataMin: Int = 30,
    @SerialName("pause_terminal_min") val rientroMinimoMin: Int = 15,
    @SerialName("no_pause_after_min") val sogliaSenzaPausaMin: Int = 360,
    @SerialName("no_pause_deduct_min") val trattenutaSenzaPausaMin: Int = 30,
) {
    fun regola(giorno: LocalDate): RegolaGiorno? = days[giorno.dayOfWeek.value]

    /** Il giorno ha la pausa con il rientro (per la sveglia e la Pausa di Timbra). */
    fun conPausa(giorno: LocalDate): Boolean = regola(giorno)?.pause == true

    /**
     * Un'uscita a quell'ora apre la pausa (si rientra dopo) solo se il giorno ha la pausa e l'uscita cade nella fascia
     * della pausa: come il motore delle ore (un'uscita fuori fascia non è pausa). Altrimenti chiude la giornata.
     */
    fun uscitaDiPausa(giorno: LocalDate, ora: LocalTime): Boolean =
        conPausa(giorno) && !ora.withSecond(0).withNano(0).isBefore(pausaDa) && !ora.withSecond(0).withNano(0).isAfter(pausaA)

    /** Problemi da mostrare prima di salvare: gli stessi limiti del backend (pydantic in orario.py). */
    fun problemi(): List<String> = buildList {
        if (days.keys.any { it !in 1..7 }) add("Giorni della settimana da lunedì a domenica")
        if (days.values.any { it.dovutiMin !in 0..1440 }) add("Ore dovute fra 0 e 24")
        if (ingressoDa.isAfter(ingressoA)) add("La fascia di ingresso finisce prima di iniziare")
        if (pausaDa.isAfter(pausaA)) add("La fascia della pausa finisce prima di iniziare")
        if (pausaConteggiataMin !in 0..240 || rientroMinimoMin !in 0..240) add("Minuti della pausa fra 0 e 240")
        if (sogliaSenzaPausaMin !in 0..1440 || trattenutaSenzaPausaMin !in 0..240) add("Soglia o trattenuta senza pausa fuori misura")
    }
}

@Serializable
data class Orario(val versions: List<VersioneOrario> = emptyList()) {
    /** La versione in vigore quel giorno (l'ultima iniziata), o null. */
    fun inVigore(giorno: LocalDate): VersioneOrario? = versions.filter { !it.validoDal.isAfter(giorno) }.maxByOrNull { it.validoDal }

    fun ordinato(): Orario = copy(versions = versions.sortedBy { it.validoDal })

    fun problemi(): List<String> = buildList {
        if (versions.size > MAX_VERSIONI) add("Al massimo $MAX_VERSIONI versioni")
        if (versions.map { it.validoDal }.toSet().size != versions.size) add("Due versioni con la stessa data di inizio")
        versions.sortedBy { it.validoDal }.forEach { v -> v.problemi().forEach { add("Dal ${v.validoDal.format(DATA)}: $it") } }
    }

    /** JSON con tutti i campi, anche quelli predefiniti (come lo manda il server). */
    fun json(): String = JSON.encodeToString(serializer(), ordinato())

    companion object {
        const val MAX_VERSIONI = 50
        private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun da(json: String): Orario = JSON.decodeFromString(serializer(), json)

        /**
         * L'orario attuale dell'utente (docs/PROGETTO.md §21): dal lunedì al giovedì 8 ore con la pausa, il venerdì 4
         * ore solo al mattino, regole predefinite. Dal 1° gennaio 2026.
         */
        fun predefinito(): Orario = Orario(
            listOf(
                VersioneOrario(
                    LocalDate.of(2026, 1, 1),
                    (1..4).associateWith { RegolaGiorno(480, true) } + (5 to RegolaGiorno(240, false)),
                ),
            ),
        )
    }
}

/** Correzione o anomalia del calcolo: codice e parametri (interi o testi "HH:MM"), come nel backend. */
data class Nota(val codice: String, val parametri: Map<String, Any>) {
    val testo: String get() = Ore.descrivi(this)
}

data class EsitoOre(
    val lavoratiMin: Int,
    val dovutiMin: Int?,
    val saldoMin: Int?,
    /** Durata vera della pausa. */
    val pausaMin: Int?,
    /** Come la conta l'ente. */
    val pausaConteggiataMin: Int?,
    /** Giornata in corso, contata fino a "adesso". */
    val aperta: Boolean,
    /** Tutte le entrate hanno la loro uscita. */
    val completa: Boolean,
    val correzioni: List<Nota>,
    val anomalie: List<Nota>,
)

object Ore {
    private fun minuti(t: LocalTime): Int = t.hour * 60 + t.minute

    private fun hm(m: Int): String = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    /** Ore valide di una giornata. [adesso] (stesso giorno) conta la coppia aperta fino a quel momento. */
    fun calcola(segni: List<Segno>, versione: VersioneOrario?, adesso: LocalDateTime? = null): EsitoOre {
        val ms = segni.sortedBy { it.quando }
        val giorno = ms.firstOrNull()?.quando?.toLocalDate() ?: adesso?.toLocalDate()
        val correzioni = mutableListOf<Nota>()
        val anomalie = mutableListOf<Nota>()
        val coppie = mutableListOf<IntArray>()  // [inizio, fine] in minuti del giorno
        var aperta: Int? = null
        for (s in ms) {
            val m = minuti(s.quando.toLocalTime())
            if (s.kind == Proposta.ENTRATA) {
                aperta?.let { anomalie += Nota("uscita_mancante", mapOf("dopo" to hm(it))) }
                aperta = m
            } else {
                val inizio = aperta
                if (inizio == null) {
                    anomalie += Nota("entrata_mancante", mapOf("prima" to hm(m)))
                    continue
                }
                coppie += intArrayOf(inizio, m)
                aperta = null
            }
        }
        var inCorso = false
        aperta?.let { inizio ->
            if (adesso != null && adesso.toLocalDate() == giorno) {
                coppie += intArrayOf(inizio, minuti(adesso.toLocalTime()))
                inCorso = true
            } else {
                anomalie += Nota("uscita_mancante", mapOf("dopo" to hm(inizio)))
            }
        }
        val completa = anomalie.isEmpty() && !inCorso

        val regola = if (versione != null && giorno != null) versione.regola(giorno) else null
        val dovuti = if (versione != null) (regola?.dovutiMin ?: 0) else null
        if (versione != null && coppie.isNotEmpty()) {
            val prima = coppie[0]
            val inizio = prima[0]
            val limite = minuti(versione.ingressoDa)
            if (inizio < limite) {
                prima[0] = minOf(limite, prima[1])
                correzioni += Nota("entrata_anticipata", mapOf("ora" to hm(inizio), "conteggiata_da" to hm(limite)))
            } else if (inizio > minuti(versione.ingressoA)) {
                anomalie += Nota("entrata_tardiva", mapOf("ora" to hm(inizio), "limite" to hm(minuti(versione.ingressoA))))
            }
        }

        var lavorati = coppie.sumOf { maxOf(it[1] - it[0], 0) }
        var pausa: Int? = null
        var pausaConteggiata: Int? = null
        if (versione != null) {
            for (i in 0 until coppie.size - 1) {
                val uscita = coppie[i][1]
                val rientro = coppie[i + 1][0]
                val vuoto = maxOf(rientro - uscita, 0)
                val inFascia = minuti(versione.pausaDa) <= uscita && uscita <= minuti(versione.pausaA)
                if (pausa == null && regola != null && regola.pause && inFascia) {
                    val contata = maxOf(vuoto, versione.pausaConteggiataMin)
                    pausa = vuoto
                    pausaConteggiata = contata
                    if (vuoto < versione.rientroMinimoMin) {
                        anomalie += Nota("rientro_troppo_presto", mapOf("durata" to vuoto, "minimo" to versione.rientroMinimoMin))
                    }
                    if (contata > vuoto) {
                        lavorati -= contata - vuoto
                        correzioni += Nota("pausa_minima", mapOf("durata" to vuoto, "conteggiata" to contata))
                    }
                } else {
                    correzioni += Nota("uscita_non_pausa", mapOf("ora" to hm(uscita), "durata" to vuoto))
                }
            }
            if (pausa == null && lavorati > versione.sogliaSenzaPausaMin) {
                lavorati -= versione.trattenutaSenzaPausaMin
                correzioni += Nota(
                    "pausa_non_fatta",
                    mapOf("tolti" to versione.trattenutaSenzaPausaMin, "oltre" to versione.sogliaSenzaPausaMin),
                )
            }
        }
        lavorati = maxOf(lavorati, 0)
        return EsitoOre(
            lavoratiMin = lavorati,
            dovutiMin = dovuti,
            saldoMin = if (dovuti != null && completa) lavorati - dovuti else null,
            pausaMin = pausa,
            pausaConteggiataMin = pausaConteggiata,
            aperta = inCorso,
            completa = completa,
            correzioni = correzioni,
            anomalie = anomalie,
        )
    }

    // --- testi in italiano, identici a orario.py (pagina per l'ente e mail) -----------------------------------------

    /** "8 h 34" */
    fun durata(m: Int): String = "${Math.floorDiv(m, 60)} h ${String.format(Locale.ROOT, "%02d", Math.floorMod(m, 60))}"

    /** "+34′", "−1 h 30", "+0′" */
    fun saldo(m: Int): String {
        val segno = if (m >= 0) "+" else "−"
        val a = kotlin.math.abs(m)
        return if (a >= 60) "$segno${a / 60} h ${String.format(Locale.ROOT, "%02d", a % 60)}" else "$segno$a′"
    }

    fun descrivi(n: Nota): String {
        val p = n.parametri
        return when (n.codice) {
            "entrata_anticipata" -> "entrata delle ${p["ora"]} conteggiata dalle ${p["conteggiata_da"]}"
            "pausa_minima" -> "pausa di ${p["durata"]}′ conteggiata ${p["conteggiata"]}′"
            "uscita_non_pausa" -> "uscita delle ${p["ora"]} (non è la pausa): conta il tempo effettivo, ${p["durata"]}′"
            "pausa_non_fatta" -> "nessuna pausa con più di ${durata((p["oltre"] as Number).toInt())} di lavoro: tolti ${p["tolti"]}′"
            "entrata_tardiva" -> "entrata delle ${p["ora"]}, dopo il limite delle ${p["limite"]}"
            "rientro_troppo_presto" -> "rientro dopo ${p["durata"]}′, prima dei ${p["minimo"]}′ che la timbratrice accetta"
            "uscita_mancante" -> "manca l'uscita dopo l'entrata delle ${p["dopo"]}"
            "entrata_mancante" -> "manca l'entrata prima dell'uscita delle ${p["prima"]}"
            else -> n.codice
        }
    }

    private val GIORNI = mapOf(1 to "lunedì", 2 to "martedì", 3 to "mercoledì", 4 to "giovedì", 5 to "venerdì", 6 to "sabato", 7 to "domenica")
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    fun nomeGiorno(n: Int): String = GIORNI.getValue(n)

    /** L'orario in vigore, in frasi: come `describe_schedule` (scheda della pagina per l'ente). */
    fun descriviOrario(v: VersioneOrario): List<String> {
        val giorni = v.days.toSortedMap().filterValues { it.dovutiMin > 0 }.entries.joinToString(", ") { (g, r) ->
            "${GIORNI.getValue(g)} ${durata(r.dovutiMin)}${if (r.pause) "" else " senza pausa"}"
        }
        return listOf(
            "In vigore dal ${v.validoDal.format(DATA)}. Ore dovute: ${giorni.ifEmpty { "nessuna" }}.",
            "Ingresso dalle ${v.ingressoDa.format(HHMM)} alle ${v.ingressoA.format(HHMM)}: un'entrata precedente vale dalle " +
                "${v.ingressoDa.format(HHMM)}.",
            "La pausa inizia tra le ${v.pausaDa.format(HHMM)} e le ${v.pausaA.format(HHMM)} e conta almeno " +
                "${v.pausaConteggiataMin} minuti; il rientro è possibile dopo ${v.rientroMinimoMin} minuti; oltre i " +
                "${v.pausaConteggiataMin} minuti conta la durata effettiva.",
            "Senza pausa, oltre ${v.sogliaSenzaPausaMin / 60} ore di lavoro si tolgono ${v.trattenutaSenzaPausaMin} minuti.",
            "Le altre uscite contano per il tempo effettivo.",
        )
    }
}

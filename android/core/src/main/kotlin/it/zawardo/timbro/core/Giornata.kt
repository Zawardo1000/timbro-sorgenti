package it.zawardo.timbro.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Quello che serve di una timbratura per ragionare sulla giornata: entrata o uscita, quando e con quale causale. */
interface Segno {
    val kind: String
    val quando: LocalDateTime

    /** Uscita di servizio o per visita medica (e il suo rientro): il tempo fuori conta come presenza. */
    val causale: Causale? get() = null
}

/** Fra l'uscita [uscita] e il [rientro]: la causale di una delle due (basta una), o null se è un'uscita normale. */
fun causaleFuori(uscita: Segno?, rientro: Segno?): Causale? = uscita?.causale ?: rientro?.causale

/**
 * Un'entrata con l'uscita che la chiude. Una delle due può mancare: uscita dimenticata, oppure (giornata in corso)
 * non ancora fatta.
 */
data class Coppia<T : Segno>(val entrata: T?, val uscita: T?) {
    val aperta: Boolean get() = entrata != null && uscita == null

    /** Tempo lavorato: per una coppia aperta conta fino a [ora], se c'è (la giornata di oggi), altrimenti niente. */
    fun durata(ora: LocalDateTime? = null): Duration {
        val inizio = entrata?.quando ?: return Duration.ZERO
        val fine = uscita?.quando ?: ora?.takeIf { it.toLocalDate() == inizio.toLocalDate() } ?: return Duration.ZERO
        return Duration.between(inizio, fine).coerceAtLeast(Duration.ZERO)
    }
}

/**
 * Le timbrature di un giorno, in ordine, raggruppate in coppie entrata → uscita. Di solito due coppie (mattina e
 * pomeriggio) con la pausa in mezzo; le anomalie (due entrate di fila, un'uscita senza entrata) diventano coppie
 * incomplete invece di spostare le etichette.
 */
class Giornata<T : Segno>(val giorno: LocalDate, segni: List<T>) {
    val segni: List<T> = segni.filter { it.quando.toLocalDate() == giorno }.sortedBy { it.quando }
    val coppie: List<Coppia<T>> = accoppia(this.segni)

    /**
     * Uscite senza causale: la pausa e la fine della giornata. Quelle di servizio o per visita medica non contano (si
     * rientra e il tempo fuori è presenza): la sveglia e la Pausa di Timbra ragionano su queste.
     */
    val uscite: Int get() = segni.count { it.kind == Proposta.USCITA && it.causale == null }

    /** L'ultima è un'entrata: si sta lavorando. */
    val inCorso: Boolean get() = coppie.lastOrNull()?.aperta == true

    /** Dopo la prima uscita (senza causale) del giorno e prima del rientro. */
    val inPausa: Boolean get() = segni.lastOrNull()?.let { it.kind == Proposta.USCITA && it.causale == null } == true && uscite == 1

    /** Fuori per servizio o per visita medica adesso: l'ultima è un'uscita con la causale. */
    val fuori: Causale? get() = segni.lastOrNull()?.takeIf { it.kind == Proposta.USCITA }?.causale

    /** Ogni uscita seguita da un rientro, in ordine, con la causale (null = pausa o uscita normale). */
    val intervalli: List<Intervallo<T>>
        get() = coppie.zipWithNext().mapNotNull { (a, b) ->
            val u = a.uscita ?: return@mapNotNull null
            val r = b.entrata ?: return@mapNotNull null
            Intervallo(u, r, causaleFuori(u, r))
        }

    /** Pausa: la prima uscita senza causale con il suo rientro, se c'è stato. */
    val pausa: Duration? get() = intervalli.firstOrNull { it.causale == null }?.durata

    /**
     * Tempo lavorato lordo (senza le regole dell'orario, che stanno in [Ore.calcola]), con il tempo fuori per servizio
     * o per visita medica; con [ora] (oggi) conta anche la coppia aperta, o il tempo fuori, fino a quel momento.
     */
    fun lavorato(ora: LocalDateTime? = null): Duration {
        val fuoriOra = segni.lastOrNull()?.takeIf { fuori != null && ora != null && ora.toLocalDate() == giorno }
            ?.let { Duration.between(it.quando, ora).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
        return coppie.fold(Duration.ZERO) { tot, c -> tot + c.durata(ora) } +
            intervalli.filter { it.causale != null }.fold(Duration.ZERO) { tot, i -> tot + i.durata } + fuoriOra
    }

    companion object {
        /** Giornate dal più recente, solo quelle con timbrature. */
        fun <T : Segno> di(segni: List<T>): List<Giornata<T>> =
            segni.groupBy { it.quando.toLocalDate() }.keys.sortedDescending().map { Giornata(it, segni) }

        private fun <T : Segno> accoppia(ordinati: List<T>): List<Coppia<T>> {
            val out = mutableListOf<Coppia<T>>()
            var aperta: T? = null
            for (s in ordinati) {
                if (s.kind == Proposta.ENTRATA) {
                    aperta?.let { out += Coppia(it, null) }  // entrata senza uscita: la coppia resta incompleta
                    aperta = s
                } else {
                    out += Coppia(aperta, s)
                    aperta = null
                }
            }
            aperta?.let { out += Coppia(it, null) }
            return out
        }
    }
}

/** Un'uscita e il rientro che la segue; [causale] di servizio o visita medica, null se è la pausa o un'uscita normale. */
data class Intervallo<T : Segno>(val uscita: T, val rientro: T, val causale: Causale?) {
    val durata: Duration get() = Duration.between(uscita.quando, rientro.quando).coerceAtLeast(Duration.ZERO)
}

/** Settimane da lunedì, con il totale lavorato. */
data class Settimana<T : Segno>(val lunedi: LocalDate, val giornate: List<Giornata<T>>) {
    fun lavorato(ora: LocalDateTime? = null): Duration = giornate.fold(Duration.ZERO) { tot, g -> tot + g.lavorato(ora) }

    companion object {
        fun lunedi(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

        /** Dalla più recente; le giornate restano in ordine dal giorno più recente. */
        fun <T : Segno> di(giornate: List<Giornata<T>>): List<Settimana<T>> =
            giornate.groupBy { lunedi(it.giorno) }.toSortedMap(compareByDescending { it })
                .map { (l, g) -> Settimana(l, g.sortedByDescending { it.giorno }) }
    }
}

/**
 * Pausa minima: il rientro non si può timbrare prima di 15 minuti dall'uscita. L'app non blocca (l'orologio del
 * telefono e quello della timbratrice possono non coincidere): prima della fine chiede conferma.
 */
object PausaMinima {
    val DURATA: Duration = Duration.ofMinutes(15)

    fun fine(uscita: LocalDateTime): LocalDateTime = uscita.plus(DURATA)

    /** Quanto manca alla fine della pausa minima; zero se è finita. */
    fun mancante(uscita: LocalDateTime, ora: LocalDateTime): Duration =
        Duration.between(ora, fine(uscita)).coerceAtLeast(Duration.ZERO)

    /**
     * Tempo che manca se si sta per timbrare un'entrata subito dopo un'uscita dello stesso giorno, altrimenti null:
     * è il caso in cui chiedere conferma. Il rientro da un'uscita di servizio o per visita medica non è una pausa.
     */
    fun daConfermare(kind: String, ultima: Segno?, ora: LocalDateTime): Duration? {
        if (kind != Proposta.ENTRATA || ultima == null || ultima.kind != Proposta.USCITA || ultima.causale != null) return null
        if (ultima.quando.toLocalDate() != ora.toLocalDate()) return null
        return mancante(ultima.quando, ora).takeIf { !it.isZero }
    }

    /** Minuti da dire all'utente: arrotondati per eccesso (mancano 30 secondi → "1 min"). */
    fun minutiArrotondati(d: Duration): Long = (d.seconds + 59) / 60
}

/** Sveglia per il rientro, impostata sull'uscita. */
object Sveglia {
    /**
     * Accesa di default alla prima uscita di un giorno con la pausa (si torna dopo), spenta dalla seconda (fine
     * giornata) e nei giorni senza pausa, come il venerdì a mezza giornata ([VersioneOrario.conPausa]). Le uscite di
     * servizio e per visita medica non contano ([Giornata.uscite]) e non hanno la sveglia.
     */
    fun predefinita(usciteGiaFatteOggi: Int, conPausa: Boolean = true): Boolean = usciteGiaFatteOggi == 0 && conPausa

    fun alle(uscita: LocalDateTime): LocalDateTime = PausaMinima.fine(uscita)
}

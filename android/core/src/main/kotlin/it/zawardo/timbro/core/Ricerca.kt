package it.zawardo.timbro.core

import java.time.LocalDate

/**
 * Ricerca per data nello Storico: quali giorni chiedere al backend e dove si trova la data nella lista.
 *
 * Il backend (`GET /api/v1/history?until=AAAA-MM-GG&days=N`, docs/PROGETTO.md) dà giorni completi in ora italiana
 * da `until` indietro di `days` giorni, al massimo [MASSIMO_GIORNI]. Per una data scelta si chiede da qualche giorno
 * dopo, così si vedono anche i giorni vicini, e la lista si apre su quella data.
 */
object Ricerca {
    /** Giorni dopo la data scelta, sopra di lei nella lista. */
    const val GIORNI_DOPO = 3L

    /** Giorni chiesti per ogni pagina (come il predefinito del backend). */
    const val GIORNI = 14

    /** Limite del backend (HISTORY_MAX_DAYS). */
    const val MASSIMO_GIORNI = 62

    /** I giorni da [fino] indietro, [giorni] compreso [fino]. */
    data class Finestra(val fino: LocalDate, val giorni: Int) {
        val dal: LocalDate get() = fino.minusDays(giorni - 1L)

        fun contiene(d: LocalDate): Boolean = !d.isBefore(dal) && !d.isAfter(fino)

        /** Parametri della richiesta al backend. */
        fun query(): String = "until=$fino&days=$giorni"
    }

    /** Le date future non si scelgono: lì non ci sono timbrature. */
    fun selezionabile(data: LocalDate, oggi: LocalDate): Boolean = !data.isAfter(oggi)

    /** Giorni da chiedere per aprire lo Storico su [data]: fino a qualche giorno dopo (mai oltre oggi). */
    fun finestra(data: LocalDate, oggi: LocalDate, giorni: Int = GIORNI): Finestra {
        require(selezionabile(data, oggi)) { "data futura: $data" }
        val fino = minOf(data.plusDays(GIORNI_DOPO), oggi)
        val minimi = (fino.toEpochDay() - data.toEpochDay() + 1).toInt()  // la data deve starci
        return Finestra(fino, giorni.coerceIn(minimi, MASSIMO_GIORNI))
    }

    /**
     * Dove sta [data] nei giorni con timbrature, ordinati dal più recente: l'indice della sua giornata, oppure quello
     * in cui andrebbe la scheda "Nessuna timbratura" (fra il giorno dopo e quello prima).
     */
    data class Posizione(val indice: Int, val presente: Boolean)

    fun posizione(giorniDalPiuRecente: List<LocalDate>, data: LocalDate): Posizione {
        val i = giorniDalPiuRecente.indexOfFirst { !it.isAfter(data) }
        return when {
            i < 0 -> Posizione(giorniDalPiuRecente.size, false)
            giorniDalPiuRecente[i] == data -> Posizione(i, true)
            else -> Posizione(i, false)
        }
    }
}

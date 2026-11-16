package it.zawardo.timbro.core

/**
 * Causale di un'uscita e del rientro che la chiude: il tempo fuori conta come presenza e non è mai la pausa (calcolo in
 * [Ore.calcola], come `orario.REASONS` nel backend). La dichiara chi timbra: non sta nel manifest firmato ma accanto,
 * sul server come le note (`reason` di `POST /punches`, dello storico e del `PATCH` admin), e si cambia dopo dallo
 * Storico. Il giustificativo per l'ente resta fuori dall'app.
 */
enum class Causale(val codice: String, val nome: String) {
    SERVIZIO("service", "servizio"),
    VISITA("medical", "visita medica");

    /** "uscita di servizio", "entrata da visita medica": come `orario.punch_label` nel backend. */
    fun etichetta(kind: String): String = when (this) {
        SERVIZIO -> if (kind == Proposta.ENTRATA) "entrata di servizio" else "uscita di servizio"
        VISITA -> if (kind == Proposta.ENTRATA) "entrata da visita medica" else "uscita per visita medica"
    }

    companion object {
        fun da(codice: String?): Causale? = entries.firstOrNull { it.codice == codice }

        /** Il tipo in minuscolo, con la causale se c'è: "entrata", "uscita di servizio", … */
        fun etichetta(kind: String, causale: Causale?): String =
            causale?.etichetta(kind) ?: if (kind == Proposta.ENTRATA) "entrata" else "uscita"
    }
}

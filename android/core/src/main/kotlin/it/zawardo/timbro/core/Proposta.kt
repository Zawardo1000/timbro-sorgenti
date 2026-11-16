package it.zawardo.timbro.core

import java.time.LocalDate

/**
 * Entrata o uscita da proporre nella schermata Timbra (resta modificabile con un tocco).
 *
 * L'opposto dell'ultima timbratura di oggi: dopo un'entrata l'uscita e viceversa. La prima del giorno è sempre
 * un'entrata: se ieri ci si è dimenticati l'uscita, la mattina dopo non si propone "uscita".
 */
object Proposta {
    const val ENTRATA = "in"
    const val USCITA = "out"

    fun tipo(ultimoTipo: String?, ultimoGiorno: LocalDate?, oggi: LocalDate): String = when {
        ultimoTipo == null || ultimoGiorno != oggi -> ENTRATA
        ultimoTipo == ENTRATA -> USCITA
        else -> ENTRATA
    }

    /**
     * Causale da proporre (le spunte di Timbra, spente di default): al rientro dopo un'uscita di servizio o per visita
     * medica di oggi, la stessa; altrimenti nessuna. Il giorno dopo si riparte senza.
     */
    fun causale(tipo: String, ultima: Segno?, oggi: LocalDate): Causale? =
        ultima?.takeIf { tipo == ENTRATA && it.kind == USCITA && it.quando.toLocalDate() == oggi }?.causale
}

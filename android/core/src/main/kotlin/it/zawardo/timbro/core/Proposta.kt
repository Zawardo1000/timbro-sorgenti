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
}

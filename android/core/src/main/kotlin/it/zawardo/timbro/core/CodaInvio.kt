package it.zawardo.timbro.core

import java.time.Duration

/**
 * Stato di una timbratura nella coda del telefono (tabella Room `coda`): prima il manifest firmato
 * (`POST /punches`), poi il video (`PUT /punches/{id}/video`). I byte del manifest, la firma e la catena di
 * attestazione si salvano una volta e si reinviano identici (docs/PROGETTO.md §20): riserializzare darebbe byte
 * diversi e il server risponderebbe 409 per sempre (stesso client_id).
 */
enum class StatoInvio {
    /** Manifest non ancora accettato dal server. */
    DA_INVIARE,
    /** Manifest accettato (c'è l'id del server), video da caricare. */
    VIDEO_DA_INVIARE,
    /** Tutto arrivato. */
    COMPLETA,
    /** Rifiuto definitivo (422, 413, 409): si avvisa, non si ritenta. */
    RIFIUTATA,
}

object Ritentativi {
    /**
     * Attesa prima del prossimo tentativo, come il worker del server: raddoppia da 10 s fino a un'ora; dopo due giorni
     * di errori ogni 6 ore. Un errore temporaneo (rete, server giù) non rende mai definitivo l'invio.
     */
    fun attesa(tentativi: Int, eta: Duration = Duration.ZERO): Duration {
        if (eta >= Duration.ofDays(2)) return Duration.ofHours(6)
        val n = (tentativi - 1).coerceIn(0, 20)
        return Duration.ofSeconds(minOf(10L shl n, 3600L))
    }

    /** Risposte del server dopo cui ritentare è inutile: richiesta non valida, video troppo grande, conflitto. */
    fun definitivo(codiceHttp: Int): Boolean = codiceHttp in setOf(409, 413, 422)
}

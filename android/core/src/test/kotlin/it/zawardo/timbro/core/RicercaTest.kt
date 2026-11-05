package it.zawardo.timbro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RicercaTest {
    private val oggi = LocalDate.of(2026, 11, 5)

    @Test
    fun `finestra da qualche giorno dopo la data, mai oltre oggi`() {
        val f = Ricerca.finestra(LocalDate.of(2026, 10, 15), oggi)
        assertEquals(LocalDate.of(2026, 10, 18), f.fino)
        assertEquals(14, f.giorni)
        assertEquals(LocalDate.of(2026, 10, 5), f.dal)
        assertTrue(f.contiene(LocalDate.of(2026, 10, 15)))
        assertEquals("until=2026-10-18&days=14", f.query())
        assertEquals(oggi, Ricerca.finestra(oggi.minusDays(1), oggi).fino)
    }

    @Test
    fun `la data sta sempre nella finestra, entro il limite del backend`() {
        assertEquals(4, Ricerca.finestra(LocalDate.of(2026, 10, 15), oggi, giorni = 1).giorni)
        assertEquals(Ricerca.MASSIMO_GIORNI, Ricerca.finestra(LocalDate.of(2026, 10, 15), oggi, giorni = 500).giorni)
    }

    @Test
    fun `date future non selezionabili`() {
        assertTrue(Ricerca.selezionabile(oggi, oggi))
        assertFalse(Ricerca.selezionabile(oggi.plusDays(1), oggi))
        assertTrue(runCatching { Ricerca.finestra(oggi.plusDays(1), oggi) }.isFailure)
    }

    @Test
    fun `posizione della data fra i giorni con timbrature`() {
        val giorni = listOf(5, 4, 3, 30, 29).map { if (it > 20) LocalDate.of(2026, 10, it) else LocalDate.of(2026, 11, it) }
        assertEquals(Ricerca.Posizione(1, true), Ricerca.posizione(giorni, LocalDate.of(2026, 11, 4)))
        // sabato 31 ottobre: nessuna timbratura, la scheda vuota va prima di venerdì 30
        assertEquals(Ricerca.Posizione(3, false), Ricerca.posizione(giorni, LocalDate.of(2026, 10, 31)))
        // più vecchia di tutte: in fondo
        assertEquals(Ricerca.Posizione(5, false), Ricerca.posizione(giorni, LocalDate.of(2026, 10, 1)))
        assertEquals(Ricerca.Posizione(0, false), Ricerca.posizione(emptyList(), oggi))
    }
}

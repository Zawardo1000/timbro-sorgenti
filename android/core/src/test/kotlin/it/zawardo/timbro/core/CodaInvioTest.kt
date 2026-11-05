package it.zawardo.timbro.core

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodaInvioTest {
    @Test
    fun `attese come il worker del server`() {
        assertEquals(Duration.ofSeconds(10), Ritentativi.attesa(1))
        assertEquals(Duration.ofSeconds(20), Ritentativi.attesa(2))
        assertEquals(Duration.ofSeconds(2560), Ritentativi.attesa(9))
        assertEquals(Duration.ofHours(1), Ritentativi.attesa(10))
        assertEquals(Duration.ofHours(1), Ritentativi.attesa(500))
        assertEquals(Duration.ofSeconds(10), Ritentativi.attesa(0))
        assertEquals(Duration.ofHours(6), Ritentativi.attesa(3, Duration.ofDays(2)))
    }

    @Test
    fun `rifiuti definitivi`() {
        assertTrue(Ritentativi.definitivo(422) && Ritentativi.definitivo(409) && Ritentativi.definitivo(413))
        assertFalse(Ritentativi.definitivo(500) || Ritentativi.definitivo(503) || Ritentativi.definitivo(401))
    }
}

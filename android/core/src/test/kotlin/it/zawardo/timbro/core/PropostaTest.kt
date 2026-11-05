package it.zawardo.timbro.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PropostaTest {
    private val oggi = LocalDate.of(2026, 11, 5)

    @Test
    fun `nessuna timbratura, si entra`() = assertEquals("in", Proposta.tipo(null, null, oggi))

    @Test
    fun `dopo un'entrata di oggi si esce`() = assertEquals("out", Proposta.tipo("in", oggi, oggi))

    @Test
    fun `dopo un'uscita di oggi si rientra`() = assertEquals("in", Proposta.tipo("out", oggi, oggi))

    @Test
    fun `la prima del giorno è un'entrata anche se ieri manca l'uscita`() =
        assertEquals("in", Proposta.tipo("in", oggi.minusDays(1), oggi))
}

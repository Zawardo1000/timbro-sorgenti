package it.zawardo.timbro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PropostaTest {
    private val oggi = LocalDate.of(2026, 11, 5)

    @Test
    fun `nessuna timbratura, si entra`() = assertEquals("in", Proposta.tipo(null, null, oggi))

    private data class S(override val kind: String, override val quando: LocalDateTime, override val causale: Causale? = null) : Segno

    @Test
    fun `al rientro da un'uscita con la causale la stessa causale, altrimenti nessuna`() {
        val servizio = S("out", oggi.atTime(10, 0), Causale.SERVIZIO)
        assertEquals(Causale.SERVIZIO, Proposta.causale("in", servizio, oggi))
        assertEquals(Causale.VISITA, Proposta.causale("in", servizio.copy(causale = Causale.VISITA), oggi))
        assertNull(Proposta.causale("out", servizio, oggi))  // scelta a mano un'altra uscita: spenta
        assertNull(Proposta.causale("in", servizio, oggi.plusDays(1)))  // il giorno dopo si riparte senza
        assertNull(Proposta.causale("in", S("out", oggi.atTime(12, 30)), oggi))
        assertNull(Proposta.causale("out", S("in", oggi.atTime(11, 0), Causale.SERVIZIO), oggi))  // dopo il rientro
        assertNull(Proposta.causale("in", null, oggi))
    }

    @Test
    fun `dopo un'entrata di oggi si esce`() = assertEquals("out", Proposta.tipo("in", oggi, oggi))

    @Test
    fun `dopo un'uscita di oggi si rientra`() = assertEquals("in", Proposta.tipo("out", oggi, oggi))

    @Test
    fun `la prima del giorno è un'entrata anche se ieri manca l'uscita`() =
        assertEquals("in", Proposta.tipo("in", oggi.minusDays(1), oggi))
}

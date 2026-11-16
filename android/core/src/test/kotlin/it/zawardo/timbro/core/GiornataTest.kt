package it.zawardo.timbro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class GiornataTest {
    private data class T(override val kind: String, override val quando: LocalDateTime, override val causale: Causale? = null) : Segno

    private val giorno = LocalDate.of(2026, 11, 4) // mercoledì
    private fun at(h: Int, m: Int, d: LocalDate = giorno) = d.atTime(h, m)
    private fun e(h: Int, m: Int, d: LocalDate = giorno, c: Causale? = null) = T("in", at(h, m, d), c)
    private fun u(h: Int, m: Int, d: LocalDate = giorno, c: Causale? = null) = T("out", at(h, m, d), c)

    private val piena = listOf(e(8, 1), u(12, 30), e(12, 58), u(17, 2))

    @Test
    fun `due entrate e due uscite fanno due coppie, la pausa e il totale`() {
        val g = Giornata(giorno, piena.shuffled())
        assertEquals(listOf(Coppia(piena[0], piena[1]), Coppia(piena[2], piena[3])), g.coppie)
        assertEquals(Duration.ofMinutes(28), g.pausa)
        assertEquals(Duration.ofMinutes(4 * 60 + 29 + 4 * 60 + 4), g.lavorato())
        assertFalse(g.inCorso)
        assertFalse(g.inPausa)
        assertEquals(2, g.uscite)
    }

    @Test
    fun `giornata in corso conta fino a ora, solo oggi`() {
        val oggi = listOf(e(7, 58), u(12, 31), e(13, 0))
        val g = Giornata(giorno, oggi)
        assertTrue(g.inCorso)
        assertEquals(Duration.ofMinutes(4 * 60 + 33 + 70), g.lavorato(at(14, 10)))
        // un'uscita dimenticata in un giorno passato non si conta fino a "adesso"
        assertEquals(Duration.ofMinutes(4 * 60 + 33), g.lavorato(at(9, 0, giorno.plusDays(1))))
    }

    @Test
    fun `in pausa dopo la prima uscita, non dopo la seconda`() {
        assertTrue(Giornata(giorno, listOf(e(7, 58), u(12, 31))).inPausa)
        assertFalse(Giornata(giorno, piena).inPausa)
        assertFalse(Giornata(giorno, listOf(e(7, 58))).inPausa)
    }

    @Test
    fun `le anomalie non spostano le etichette`() {
        val g = Giornata(giorno, listOf(e(8, 0), e(8, 1), u(12, 0), u(17, 0)))
        assertEquals(
            listOf(Coppia(e(8, 0), null), Coppia(e(8, 1), u(12, 0)), Coppia(null, u(17, 0))),
            g.coppie,
        )
        assertEquals(Duration.ofMinutes(3 * 60 + 59), g.lavorato())
    }

    @Test
    fun `solo le timbrature di quel giorno`() {
        val g = Giornata(giorno, piena + e(8, 0, giorno.plusDays(1)))
        assertEquals(4, g.segni.size)
    }

    @Test
    fun `giornate e settimane dal piu recente, con i totali`() {
        val lunedi = LocalDate.of(2026, 11, 2)
        val venerdiPrima = LocalDate.of(2026, 10, 30)
        val segni = listOf(e(8, 0, lunedi), u(12, 0, lunedi), e(8, 0, venerdiPrima), u(16, 0, venerdiPrima)) + piena
        val giornate = Giornata.di(segni)
        assertEquals(listOf(giorno, lunedi, venerdiPrima), giornate.map { it.giorno })
        val settimane = Settimana.di(giornate)
        assertEquals(listOf(lunedi, LocalDate.of(2026, 10, 26)), settimane.map { it.lunedi })
        assertEquals(listOf(giorno, lunedi), settimane[0].giornate.map { it.giorno })
        assertEquals(Duration.ofMinutes(4 * 60 + 8 * 60 + 33), settimane[0].lavorato())
    }

    @Test
    fun `pausa minima di 15 minuti dall'uscita`() {
        val uscita = at(12, 31)
        assertEquals(at(12, 46), PausaMinima.fine(uscita))
        assertEquals(Duration.ofMinutes(5), PausaMinima.mancante(uscita, at(12, 41)))
        assertEquals(Duration.ZERO, PausaMinima.mancante(uscita, at(13, 0)))
        assertEquals(1L, PausaMinima.minutiArrotondati(Duration.ofSeconds(30)))
        assertEquals(5L, PausaMinima.minutiArrotondati(Duration.ofMinutes(5)))
    }

    @Test
    fun `conferma solo per un rientro troppo presto`() {
        val uscita = u(12, 31)
        assertEquals(Duration.ofMinutes(5), PausaMinima.daConfermare("in", uscita, at(12, 41)))
        assertNull(PausaMinima.daConfermare("in", uscita, at(12, 46)))       // pausa finita
        assertNull(PausaMinima.daConfermare("out", uscita, at(12, 41)))      // un'altra uscita: niente pausa
        assertNull(PausaMinima.daConfermare("in", e(8, 0), at(8, 5)))        // l'ultima è un'entrata
        assertNull(PausaMinima.daConfermare("in", null, at(8, 5)))
        assertNull(PausaMinima.daConfermare("in", u(23, 55, giorno.minusDays(1)), at(0, 2)))  // ieri
    }

    @Test
    fun `sveglia accesa alla prima uscita, spenta alla seconda`() {
        assertTrue(Sveglia.predefinita(0))
        assertFalse(Sveglia.predefinita(1))
        assertEquals(at(12, 46), Sveglia.alle(at(12, 31)))
    }

    @Test
    fun `l'uscita di servizio non e' la pausa ne' la fine della giornata`() {
        val servizio = u(10, 0, c = Causale.SERVIZIO)
        val fuori = Giornata(giorno, listOf(e(8, 0), servizio))
        assertEquals(Causale.SERVIZIO, fuori.fuori)
        assertFalse(fuori.inPausa)
        assertEquals(0, fuori.uscite)  // la sveglia della pausa resta quella della prima uscita normale
        assertEquals(Duration.ofMinutes(120 + 45), fuori.lavorato(at(10, 45)))  // il tempo fuori conta fino ad adesso
        assertEquals(Duration.ofMinutes(120), fuori.lavorato(at(9, 0, giorno.plusDays(1))))  // un altro giorno no

        val segni = listOf(e(8, 0), servizio, e(11, 0, c = Causale.SERVIZIO), u(12, 30))
        val inPausa = Giornata(giorno, segni)
        assertNull(inPausa.fuori)
        assertTrue(inPausa.inPausa)  // la 12:30 è la prima uscita che conta
        assertEquals(1, inPausa.uscite)
        val piena = Giornata(giorno, segni + listOf(e(13, 0), u(16, 30)))
        assertEquals(Duration.ofMinutes(30), piena.pausa)  // non i 60 minuti di servizio
        assertEquals(listOf(Causale.SERVIZIO, null), piena.intervalli.map { it.causale })
        assertEquals(Duration.ofMinutes(120 + 60 + 90 + 210), piena.lavorato())
        assertFalse(piena.inPausa)
        assertEquals(2, piena.uscite)
    }

    @Test
    fun `basta la causale su una delle due timbrature`() {
        val g = Giornata(giorno, listOf(e(8, 0), u(10, 0), e(10, 30, c = Causale.VISITA), u(12, 0)))
        assertEquals(Causale.VISITA, g.intervalli.single().causale)
        assertNull(g.pausa)
        assertEquals(causaleFuori(u(10, 0, c = Causale.SERVIZIO), e(11, 0)), Causale.SERVIZIO)
        assertNull(causaleFuori(u(10, 0), e(11, 0)))
    }

    @Test
    fun `rientro da servizio o da visita senza conferma della pausa minima`() {
        assertNull(PausaMinima.daConfermare("in", u(12, 31, c = Causale.SERVIZIO), at(12, 35)))
        assertNull(PausaMinima.daConfermare("in", u(12, 31, c = Causale.VISITA), at(12, 35)))
    }

    @Test
    fun `etichette con la causale, come il backend`() {
        assertEquals(
            listOf("entrata", "uscita", "entrata di servizio", "uscita di servizio", "entrata da visita medica", "uscita per visita medica"),
            listOf(null, Causale.SERVIZIO, Causale.VISITA).flatMap { c -> listOf("in", "out").map { Causale.etichetta(it, c) } },
        )
        assertEquals(Causale.VISITA, Causale.da("medical"))
        assertNull(Causale.da("ferie"))
        assertNull(Causale.da(null))
    }
}

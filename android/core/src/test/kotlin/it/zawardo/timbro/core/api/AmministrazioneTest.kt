package it.zawardo.timbro.core.api

import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.RegolaGiorno
import it.zawardo.timbro.core.VersioneOrario
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Risoluzione dell'orario dalle assegnazioni (come `accounts.resolved_schedules`) e regole dell'amministrazione. */
class AmministrazioneTest {
    private fun d(s: String): LocalDate = LocalDate.parse(s)
    private val v1 = Orario.predefinito().versions.single()  // dal 2026-01-01, lun-gio 8 h, ven 4 h
    private val nove = VersioneOrario(d("2026-01-01"), (1..4).associateWith { RegolaGiorno(540) })

    @Test
    fun `stessi casi del backend - versione condivisa e cambio di articolazione`() {
        // l'ente cambia la pausa minima per tutti dal 2026-06-01; dal 2026-09-01 si passa ai 4 giorni da 9 ore
        val comune = Articolazione(1, "36 h su 5 giorni", listOf(v1, v1.copy(validoDal = d("2026-06-01"), pausaConteggiataMin = 20)))
        val quattro = Articolazione(2, "4 giorni da 9 ore", listOf(nove))
        val orario = Amministrazione.risolvi(
            listOf(Assegnazione(2, null, d("2026-09-01")), Assegnazione(1, null, d("2026-01-01"))),
            listOf(comune, quattro),
        )
        assertEquals(listOf("2026-01-01", "2026-06-01", "2026-09-01"), orario.versions.map { it.validoDal.toString() })
        assertEquals(30, orario.inVigore(d("2026-03-02"))!!.pausaConteggiataMin)
        assertEquals(20, orario.inVigore(d("2026-07-06"))!!.pausaConteggiataMin)
        val settembre = orario.inVigore(d("2026-09-07"))!!
        assertEquals(540, settembre.days[1]!!.dovutiMin)
        assertNull(settembre.days[5])
    }

    @Test
    fun `l'assegnazione parte dalla versione in vigore quel giorno`() {
        val a = Articolazione(1, "a", listOf(v1, v1.copy(validoDal = d("2026-06-01"), pausaConteggiataMin = 20)))
        // assegnata dal 2026-03-15: la prima versione vale dal 15, non dal 1° gennaio
        val o = Amministrazione.risolvi(listOf(Assegnazione(1, null, d("2026-03-15"))), listOf(a))
        assertEquals(listOf("2026-03-15", "2026-06-01"), o.versions.map { it.validoDal.toString() })
        assertNull(o.inVigore(d("2026-03-14")))
        // un'articolazione che comincia dopo l'assegnazione: fino ad allora niente orario
        val tardi = Articolazione(2, "b", listOf(nove.copy(validoDal = d("2026-05-01"))))
        val o2 = Amministrazione.risolvi(listOf(Assegnazione(2, null, d("2026-04-01"))), listOf(tardi))
        assertNull(o2.inVigore(d("2026-04-20")))
        assertEquals(540, o2.inVigore(d("2026-05-04"))!!.days[1]!!.dovutiMin)
        // nessuna assegnazione o articolazione sparita: orario vuoto
        assertTrue(Amministrazione.risolvi(emptyList(), listOf(a)).versions.isEmpty())
        assertTrue(Amministrazione.risolvi(listOf(Assegnazione(9, null, d("2026-01-01"))), listOf(a)).versions.isEmpty())
    }

    @Test
    fun `regole dell'amministrazione come il server`() {
        assertTrue(Amministrazione.nomeUtenteValido("bianchi"))
        assertTrue(Amministrazione.nomeUtenteValido("m.rossi-2_b"))
        assertFalse(Amministrazione.nomeUtenteValido("Con Spazi"))
        assertFalse(Amministrazione.nomeUtenteValido("Maiuscole"))
        assertFalse(Amministrazione.nomeUtenteValido(""))
        assertFalse(Amministrazione.nomeUtenteValido("a".repeat(65)))
        assertTrue(Amministrazione.problemiNuovoUtente("rossi", "Anna Rossi", "42", listOf("bianchi")).isEmpty())
        assertEquals(1, Amministrazione.problemiNuovoUtente("bianchi", "", "", listOf("bianchi")).size)
        assertEquals(listOf("Esiste già un'articolazione con questo nome"), Amministrazione.problemiNomeArticolazione(" 36 H su 5 giorni ", listOf("36 h su 5 giorni")))
        assertEquals(listOf("Serve un nome"), Amministrazione.problemiNomeArticolazione("  ", emptyList()))
        val stessaData = listOf(RichiestaAssegnazione(1, d("2026-01-01")), RichiestaAssegnazione(2, d("2026-01-01")))
        assertEquals(1, Amministrazione.problemiAssegnazioni(stessaData).size)

        val admin = UtenteSistema(2, "bianchi", "admin")
        val utente = UtenteSistema(1, "prova", "user")
        assertTrue(Amministrazione.ultimoAdmin(listOf(admin, utente), 2))
        assertFalse(Amministrazione.ultimoAdmin(listOf(admin, utente), 1))
        assertFalse(Amministrazione.ultimoAdmin(listOf(admin, utente.copy(role = "admin")), 2))
    }

    @Test
    fun `assegnazione in vigore di un utente`() {
        val u = UtenteSistema(2, "bianchi", "admin", assignments = listOf(Assegnazione(1, "a", d("2026-01-01")), Assegnazione(2, "b", d("2026-09-01"))))
        assertEquals(1L, u.assegnazione(d("2026-08-31"))!!.articolazioneId)
        assertEquals(2L, u.assegnazione(d("2026-09-01"))!!.articolazioneId)
        assertNull(u.assegnazione(d("2025-12-31")))
    }
}

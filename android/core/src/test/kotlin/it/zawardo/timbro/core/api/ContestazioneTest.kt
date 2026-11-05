package it.zawardo.timbro.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContestazioneTest {

    @Test
    fun `indirizzi email con la regola del server`() {
        listOf("a@b.it", "ufficio.personale@ente.example.it", "nome+tag@x.co").forEach { assertTrue(it, Contestazioni.emailValida(it)) }
        listOf("", "senza-chiocciola.it", "a@b", "a @b.it", "a@b .it", "@b.it", "a@@b.it", "a@b.it ").forEach {
            assertFalse(it, Contestazioni.emailValida(it))
        }
        val lungo = "a".repeat(250) + "@b.it"  // 255 caratteri
        assertFalse(Contestazioni.emailValida(lungo))
        assertTrue(Contestazioni.emailValida("a".repeat(249) + "@b.it"))
    }

    @Test
    fun `limiti del profilo`() {
        assertTrue(Contestazioni.problemiProfilo("Mario Rossi", "012345", listOf("a@b.it")).isEmpty())
        assertTrue(Contestazioni.problemiProfilo("", "", emptyList()).isEmpty())  // si può anche svuotare
        // indirizzo personale (Reply-To e copia): facoltativo, controllato come gli altri
        assertTrue(Contestazioni.problemiProfilo("Mario Rossi", "012345", emptyList(), "mario@b.it").isEmpty())
        assertTrue(Contestazioni.problemiProfilo("Mario Rossi", "012345", emptyList(), "").isEmpty())
        assertEquals(listOf("Il tuo indirizzo email non è valido"), Contestazioni.problemiProfilo("Mario Rossi", "012345", emptyList(), "mario@"))
        val troppi = (1..11).map { "u$it@ente.it" }
        val problemi = Contestazioni.problemiProfilo("x".repeat(101), "1".repeat(41), troppi + "non-valido" + "A@b.it" + "a@B.it")
        assertTrue(problemi.any { "Nome troppo lungo" in it })
        assertTrue(problemi.any { "Matricola troppo lunga" in it })
        assertTrue(problemi.any { "Al massimo 10" in it })
        assertTrue(problemi.any { it == "Indirizzo non valido: non-valido" })
        assertTrue(problemi.any { "Indirizzo ripetuto" in it })
    }

    @Test
    fun `limiti della richiesta`() {
        assertTrue(Contestazioni.problemiRichiesta(15, listOf("a@b.it"), null).isEmpty())
        assertTrue(Contestazioni.problemiRichiesta(60, emptyList(), "x".repeat(1000)).isEmpty())
        assertEquals(3, Contestazioni.problemiRichiesta(61, listOf("rotto"), "x".repeat(1001)).size)
        assertEquals(1, Contestazioni.problemiRichiesta(0, emptyList(), null).size)
        assertEquals(listOf(7, 15, 30, 60), Contestazioni.DURATE)
    }

    @Test
    fun `stati della mail, anche quelli che l'app non conosce`() {
        assertEquals(StatoAvviso.INVIATA, StatoAvviso.da("sent"))
        assertEquals(StatoAvviso.FALLITA, StatoAvviso.da("failed"))
        assertEquals(StatoAvviso.IN_INVIO, StatoAvviso.da("qualcosa-di-nuovo"))
    }

    @Test
    fun `profilo completo solo con nome e matricola`() {
        assertTrue(Profilo("Mario Rossi", "012345").completo)
        assertFalse(Profilo("Mario Rossi", " ").completo)
        assertFalse(Profilo(null, "1").completo)
    }
}

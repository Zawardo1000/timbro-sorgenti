package it.zawardo.timbro.core

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcolo delle ore valide: i casi condivisi con il backend (tests/fixtures/contratto/orario.json, tests/test_orario.py). */
class OrarioTest {
    private data class S(override val kind: String, override val quando: LocalDateTime) : Segno

    private fun esempi(nome: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/contratto").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "tests/fixtures/contratto non trovata risalendo da ${System.getProperty("user.dir")}" }
        return Json.parseToJsonElement(File(dir, "tests/fixtures/contratto/$nome").readText()).jsonObject
    }

    private val dati = esempi("orario.json")
    private val orario = Orario.JSON.decodeFromJsonElement(Orario.serializer(), dati["schedule"]!!)

    private fun intero(e: JsonElement?): Int? = if (e == null || e is JsonNull) null else e.jsonPrimitive.int

    /** Parametri come nel JSON: interi per i numeri, testi per le ore. */
    private fun note(e: JsonElement): List<Pair<String, Map<String, Any>>> = e.jsonArray.map { n ->
        val o = n.jsonObject
        o["code"]!!.jsonPrimitive.content to o["params"]!!.jsonObject.mapValues { (_, v) ->
            val p = v.jsonPrimitive
            if (p.isString) p.content else p.int
        }
    }

    @Test
    fun `casi condivisi con il backend, campo per campo`() {
        val casi = dati["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(14, casi.size)
        for (c in casi) {
            val nome = c["name"]!!.jsonPrimitive.content
            val giorno = LocalDate.parse(c["day"]!!.jsonPrimitive.content)
            val segni = c["marks"]!!.jsonArray.map { m ->
                val (k, hm) = m.jsonArray.map { it.jsonPrimitive.content }
                S(k, giorno.atTime(LocalTime.parse(hm)))
            }
            val adesso = c["now"]?.let { giorno.atTime(LocalTime.parse(it.jsonPrimitive.content)) }
            val r = Ore.calcola(segni, orario.inVigore(giorno), adesso)
            assertEquals(nome, intero(c["worked_min"]), r.lavoratiMin)
            assertEquals(nome, intero(c["due_min"]), r.dovutiMin)
            assertEquals(nome, intero(c["balance_min"]), r.saldoMin)
            assertEquals(nome, intero(c["pause_min"]), r.pausaMin)
            assertEquals(nome, intero(c["pause_counted_min"]), r.pausaConteggiataMin)
            assertEquals(nome, c["open"]!!.jsonPrimitive.booleanOrNull, r.aperta)
            assertEquals(nome, c["complete"]!!.jsonPrimitive.booleanOrNull, r.completa)
            assertEquals(nome, note(c["adjustments"]!!), r.correzioni.map { it.codice to it.parametri })
            assertEquals(nome, note(c["anomalies"]!!), r.anomalie.map { it.codice to it.parametri })
            (r.correzioni + r.anomalie).forEach { assertNotEquals(nome, it.codice, it.testo) }  // ogni codice ha il suo testo
        }
    }

    @Test
    fun `versioni dell'orario`() {
        assertNull(orario.inVigore(LocalDate.of(2025, 12, 31)))
        assertEquals(240, orario.inVigore(LocalDate.of(2026, 11, 4))!!.days[5]!!.dovutiMin)
        assertFalse(5 in orario.inVigore(LocalDate.of(2027, 1, 4))!!.days)
        val venerdi = LocalDate.of(2026, 11, 6)
        assertFalse(orario.inVigore(venerdi)!!.conPausa(venerdi))
        assertTrue(orario.inVigore(venerdi.minusDays(1))!!.conPausa(venerdi.minusDays(1)))
        assertFalse(orario.inVigore(venerdi.plusDays(1))!!.conPausa(venerdi.plusDays(1)))  // sabato: niente orario
    }

    @Test
    fun `JSON con i nomi e i formati del backend`() {
        val json = Orario.predefinito().json()
        val o = Json.parseToJsonElement(json).jsonObject["versions"]!!.jsonArray[0].jsonObject
        assertEquals("2026-01-01", o["valid_from"]!!.jsonPrimitive.content)
        assertEquals("07:30:00", o["entry_from"]!!.jsonPrimitive.content)  // anche con i secondi a zero
        assertEquals("13:15:00", o["pause_to"]!!.jsonPrimitive.content)
        assertEquals(setOf("1", "2", "3", "4", "5"), o["days"]!!.jsonObject.keys)
        assertEquals(false, o["days"]!!.jsonObject["5"]!!.jsonObject["pause"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(30, o["no_pause_deduct_min"]!!.jsonPrimitive.intOrNull)  // anche i valori predefiniti
        assertEquals(Orario.predefinito(), Orario.da(json))
        // il JSON degli esempi rientra uguale
        assertEquals(orario, Orario.da(orario.json()))
    }

    @Test
    fun `problemi prima di salvare, come il server`() {
        assertTrue(Orario.predefinito().problemi().isEmpty())
        val v = Orario.predefinito().versions[0]
        val doppio = Orario(listOf(v, v))
        assertTrue(doppio.problemi().any { "stessa data" in it })
        val rotta = v.copy(ingressoDa = LocalTime.of(10, 0), days = mapOf(8 to RegolaGiorno(60)))
        val problemi = Orario(listOf(rotta)).problemi()
        assertTrue(problemi.any { "ingresso" in it })
        assertTrue(problemi.any { "lunedì a domenica" in it })
    }

    @Test
    fun `testi uguali a quelli del backend`() {
        assertEquals("8 h 34", Ore.durata(514))
        assertEquals("0 h 00", Ore.durata(0))
        assertEquals("+34′", Ore.saldo(34))
        assertEquals("−1 h 30", Ore.saldo(-90))
        assertEquals("+0′", Ore.saldo(0))
        assertEquals("−5′", Ore.saldo(-5))
        assertEquals("pausa di 29′ conteggiata 30′", Nota("pausa_minima", mapOf("durata" to 29, "conteggiata" to 30)).testo)
        assertEquals(
            "nessuna pausa con più di 6 h 00 di lavoro: tolti 30′",
            Nota("pausa_non_fatta", mapOf("tolti" to 30, "oltre" to 360)).testo,
        )
        assertEquals("entrata delle 07:20 conteggiata dalle 07:30", Nota("entrata_anticipata", mapOf("ora" to "07:20", "conteggiata_da" to "07:30")).testo)
        val righe = Ore.descriviOrario(orario.versions[0])
        assertEquals(
            "In vigore dal 01/01/2026. Ore dovute: lunedì 8 h 00, martedì 8 h 00, mercoledì 8 h 00, giovedì 8 h 00, venerdì 4 h 00 senza pausa.",
            righe[0],
        )
        assertEquals("Ingresso dalle 07:30 alle 09:30: un'entrata precedente vale dalle 07:30.", righe[1])
        assertEquals("Senza pausa, oltre 6 ore di lavoro si tolgono 30 minuti.", righe[3])
    }

    @Test
    fun `senza orario solo ore lorde`() {
        val g = LocalDate.of(2026, 11, 4)
        val r = Ore.calcola(listOf(S("in", g.atTime(7, 0)), S("out", g.atTime(15, 0))), null)
        assertEquals(480, r.lavoratiMin)
        assertNull(r.dovutiMin)
        assertNull(r.saldoMin)
        assertTrue(r.correzioni.isEmpty())
    }

    @Test
    fun `sveglia accesa solo alla prima uscita di un giorno con la pausa`() {
        assertTrue(Sveglia.predefinita(0, conPausa = true))
        assertFalse(Sveglia.predefinita(0, conPausa = false))  // venerdì a mezza giornata
        assertFalse(Sveglia.predefinita(1, conPausa = true))
    }

    @Test
    fun `un'uscita apre la pausa solo nella fascia e nei giorni con la pausa`() {
        val v = Orario.predefinito().versions.single()
        val giovedi = LocalDate.of(2026, 11, 5)
        val venerdi = LocalDate.of(2026, 11, 6)
        assertTrue(v.uscitaDiPausa(giovedi, LocalTime.of(12, 31, 4)))
        assertTrue(v.uscitaDiPausa(giovedi, LocalTime.of(12, 15)))
        assertTrue(v.uscitaDiPausa(giovedi, LocalTime.of(13, 15, 59)))  // i secondi non contano, come sul terminale
        assertFalse(v.uscitaDiPausa(giovedi, LocalTime.of(18, 26, 9)))  // fine giornata, non pausa
        assertFalse(v.uscitaDiPausa(giovedi, LocalTime.of(11, 30)))
        assertFalse(v.uscitaDiPausa(venerdi, LocalTime.of(12, 31)))  // venerdì senza pausa
    }
}

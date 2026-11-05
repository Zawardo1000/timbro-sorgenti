package it.zawardo.timbro.core.api

import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.Segno
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testo esatto delle mail (tests/fixtures/contratto/mail.json, generato da timbro/mailer.py): l'anteprima dell'app e
 * la mail del server devono coincidere carattere per carattere.
 */
class TestoMailTest {
    private data class S(override val kind: String, override val quando: LocalDateTime) : Segno

    private fun esempi(nome: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/contratto").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "tests/fixtures/contratto non trovata risalendo da ${System.getProperty("user.dir")}" }
        return Json.parseToJsonElement(File(dir, "tests/fixtures/contratto/$nome").readText()).jsonObject
    }

    @Test
    fun `stesso testo del server per tutti i casi`() {
        val orario = Orario.JSON.decodeFromJsonElement(Orario.serializer(), esempi("orario.json")["schedule"]!!)
        val casi = esempi("mail.json")["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, casi.size)
        for (c in casi) {
            val nome = c["name"]!!.jsonPrimitive.content
            val giorno = LocalDate.parse(c["day"]!!.jsonPrimitive.content)
            val segni = c["marks"]!!.jsonArray.map { m ->
                val (k, hm) = m.jsonArray.map { it.jsonPrimitive.content }
                S(k, giorno.atTime(LocalTime.parse(hm)))
            }
            val ore = if (c["with_schedule"]!!.jsonPrimitive.boolean) Ore.calcola(segni, orario.inVigore(giorno)) else null
            c["hours"]?.takeIf { it !is JsonNull }?.jsonObject?.let { h ->
                assertEquals(nome, h["worked_min"]!!.jsonPrimitive.int, ore!!.lavoratiMin)
                assertEquals(nome, h["due_min"]!!.jsonPrimitive.int, ore.dovutiMin)
                val saldo = h["balance_min"]
                assertEquals(nome, if (saldo == null || saldo is JsonNull) null else saldo.jsonPrimitive.int, ore.saldoMin)
            }
            val contestata = c["punch"]?.takeIf { it !is JsonNull }?.jsonArray?.let { p ->
                p[0].jsonPrimitive.content to LocalTime.parse(p[1].jsonPrimitive.content)
            }
            val nota = c["note"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val m = TestoMail.mail(
                c["full_name"]!!.jsonPrimitive.content, c["employee_number"]!!.jsonPrimitive.content, giorno, contestata,
                segni.map { it.kind to it.quando.toLocalTime() }, ore, c["url"]!!.jsonPrimitive.content,
                LocalDate.parse(c["valid_until"]!!.jsonPrimitive.content), nota,
            )
            assertEquals(nome, c["subject"]!!.jsonPrimitive.content, m.oggetto)
            assertEquals(nome, c["body"]!!.jsonPrimitive.content, m.corpo)
        }
    }

    @Test
    fun `nota vuota o di soli spazi non compare`() {
        val g = LocalDate.of(2026, 11, 5)
        val m = TestoMail.mail("A B", "1", g, "in" to LocalTime.of(7, 58), listOf("in" to LocalTime.of(7, 58)), null, "u", g, "   ")
        assertFalse("Nota:" in m.corpo)
        assertTrue("in particolare per l'entrata delle 07:58." in m.corpo)
        assertTrue("giovedì 5 novembre 2026" in m.corpo)
        assertTrue("le marche temporali e le istruzioni" in m.corpo)  // senza orario niente calcolo delle ore
    }
}

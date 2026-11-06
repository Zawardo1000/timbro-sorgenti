package it.zawardo.timbro.core.api

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Esempi condivisi con il backend (tests/fixtures/contratto, controllati anche da tests/test_contratto.py). */
class ContrattoTest {

    private fun esempi(nome: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/contratto").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "tests/fixtures/contratto non trovata risalendo da ${System.getProperty("user.dir")}" }
        return Json.parseToJsonElement(File(dir, "tests/fixtures/contratto/$nome").readText()).jsonObject
    }

    private fun pemDer(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    @Test
    fun `stringa canonica e firma come il server`() {
        val dati = esempi("firma_admin.json")
        val ec = KeyFactory.getInstance("EC")
        val pubblica = ec.generatePublic(X509EncodedKeySpec(pemDer(dati["public_key_pem"]!!.jsonPrimitive.content)))
        val privata = ec.generatePrivate(PKCS8EncodedKeySpec(pemDer(dati["private_key_pkcs8_pem"]!!.jsonPrimitive.content)))
        val vettori = dati["vectors"]!!.jsonArray
        assertTrue(vettori.size >= 4)
        for (v in vettori.map { it.jsonObject }) {
            fun s(k: String) = v[k]!!.jsonPrimitive.content
            val canonica = FirmaAdmin.canonica(s("method"), s("path"), s("query"), s("time"), s("nonce"), s("body").toByteArray())
            assertEquals(s("canonical"), canonica.toString(Charsets.UTF_8))
            // la firma fatta da Python si verifica in Java: stessa stringa, stesso formato DER
            val verifica = Signature.getInstance("SHA256withECDSA").apply { initVerify(pubblica); update(canonica) }
            assertTrue(verifica.verify(Base64.getDecoder().decode(s("signature"))))
            // e le intestazioni dell'app portano una firma che il server accetta
            val h = FirmaAdmin.intestazioni(s("method"), s("path"), s("query"), s("body").toByteArray(), { msg ->
                Signature.getInstance("SHA256withECDSA").apply { initSign(privata); update(msg) }.sign()
            }, ora = s("time"), nonce = s("nonce"))
            val firma = Base64.getDecoder().decode(h.getValue("X-Timbro-Signature"))
            assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pubblica); update(canonica) }.verify(firma))
            assertEquals(s("time"), h["X-Timbro-Time"])
        }
    }

    @Test
    fun `ora e nonce nel formato che il server accetta`() {
        assertEquals("2026-11-05T12:00:00.000Z", FirmaAdmin.ora(Instant.parse("2026-11-05T12:00:00Z")))
        assertEquals("2026-11-05T12:00:05.250Z", FirmaAdmin.ora(Instant.parse("2026-11-05T12:00:05.250123Z")))
        val n = FirmaAdmin.nonce()
        assertEquals(24, n.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{16,64}$").matches(n))
        assertFalse(n == FirmaAdmin.nonce())
    }

    @Test
    fun `richieste che il server rifiuterebbe`() {
        val ok = "nonce_di_prova-0123"
        assertThrows(IllegalArgumentException::class.java) { FirmaAdmin.canonica("GET", "/a#b", null, "t", ok, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { FirmaAdmin.canonica("GET", "/a", "x=1#", "t", ok, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { FirmaAdmin.canonica("GET", "/a", null, "t", "corto", ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { FirmaAdmin.canonica("GET", "a", null, "t", ok, ByteArray(0)) }
    }

    @Test
    fun `risposte del server lette dai modelli`() {
        val dati = esempi("risposte.json")
        val reg = ApiJson.decodeFromJsonElement(Registrazione.serializer(), dati["register"]!!)
        assertEquals(36, reg.deviceId.length)

        val stato = ApiJson.decodeFromJsonElement(StatoTimbratura.serializer(), dati["punch_status"]!!)
        assertEquals("out", stato.kind)
        assertTrue(stato.videoRicevuto && stato.marcataQualificata)
        assertEquals(true, stato.attestation?.ok)
        assertEquals(10.0, stato.location!!.precisioneM!!, 0.0)
        assertNull(stato.stamps[1].ora)

        val storico = ApiJson.decodeFromJsonElement(Storico.serializer(), dati["history"]!!)
        val giorno = storico.days.single()
        assertEquals(LocalDate.of(2026, 11, 5), giorno.giorno)
        assertEquals(listOf("in", "out"), giorno.punches.map { it.kind })
        assertNull(giorno.punches[1].attestazioneOk)
        assertTrue(giorno.punches[1].contested && giorno.punches[1].keep)
        assertEquals("mancante", giorno.punches[1].video)
        assertEquals("2026-10-22", storico.nextUntil)
    }

    @Test
    fun `profilo e contestazione letti dai modelli`() {
        val dati = esempi("risposte.json")
        val profilo = ApiJson.decodeFromJsonElement(Profilo.serializer(), dati["profile"]!!)
        assertEquals("Mario Rossi", profilo.nome)
        assertEquals("012345", profilo.matricola)
        assertEquals("mario.rossi@example.it", profilo.email)
        assertEquals(2, profilo.destinatari.size)
        assertTrue(profilo.mailConfigurata && profilo.completo)
        assertTrue(profilo.destinatari.all(Contestazioni::emailValida))

        val esito = ApiJson.decodeFromJsonElement(EsitoContestazione.serializer(), dati["contest"]!!)
        assertEquals(LocalDate.of(2026, 11, 4), esito.giorno)
        val punch = esito.punch!!
        assertTrue(punch.contested && punch.keep)
        assertEquals("out", punch.kind)
        val ore = esito.hours!!
        assertEquals(514, ore.lavoratiMin)
        assertEquals(34, ore.saldoMin)
        assertTrue(ore.complete && !ore.open)
        assertEquals("pausa di 29′ conteggiata 30′", ore.adjustments.single().nota().testo)  // stessi codici del motore dell'app
        assertEquals(listOf(121L, 122L, 123L, 124L), esito.share.punchIds)
        assertTrue(esito.share.download && esito.share.active)
        assertTrue(esito.share.url!!.contains("/s/"))
        assertEquals(listOf("2026-11-04"), esito.share.days)
        val avviso = esito.notice!!
        assertEquals(StatoAvviso.IN_INVIO, avviso.stato)
        assertEquals(esito.share.id, avviso.shareId)
        assertNull(avviso.inviatoIl)
        assertEquals(TestoMail.oggetto("Mario Rossi", "012345", LocalDate.of(2026, 11, 4)), avviso.subject)
        assertEquals("2026-11-04", avviso.day)
        assertEquals(punch.id, avviso.punchId)
        // segnalazione di tutta la giornata: niente punch, l'avviso non ha la timbratura
        val giornata = ApiJson.decodeFromString(
            EsitoContestazione.serializer(),
            """{"day":"2026-11-04","punch":null,"share":${dati["contest"]!!.jsonObject["share"]},"notice":null,"hours":null}""",
        )
        assertNull(giornata.punch)
        assertNull(giornata.hours)
    }

    @Test
    fun `orario dell'utente letto con il modello dell'app`() {
        val dati = esempi("risposte.json")
        val orario = it.zawardo.timbro.core.Orario.JSON.decodeFromJsonElement(it.zawardo.timbro.core.Orario.serializer(), dati["schedule"]!!)
        assertEquals(2, orario.versions.size)
        val oggi = LocalDate.of(2026, 11, 6)  // venerdì
        val v = orario.inVigore(oggi)!!
        assertEquals(240, v.days[5]!!.dovutiMin)
        assertTrue(!v.conPausa(oggi))
        assertEquals(java.time.LocalTime.of(12, 15), v.pausaDa)
        assertTrue(orario.problemi().isEmpty())
        // e torna al server con gli stessi campi
        val rimandato = Json.parseToJsonElement(orario.json()).jsonObject
        assertEquals(dati["schedule"]!!.jsonObject["versions"]!!.jsonArray.size, rimandato["versions"]!!.jsonArray.size)
        assertEquals(
            dati["schedule"]!!.jsonObject["versions"]!!.jsonArray[0].jsonObject.keys,
            rimandato["versions"]!!.jsonArray[0].jsonObject.keys,
        )
    }

    @Test
    fun `richieste al server con i nomi dei campi del backend`() {
        val r = ApiJson.encodeToString(RichiestaContestazione.serializer(), RichiestaContestazione(15, listOf("a@b.it"), "nota"))
        assertEquals("""{"days":15,"recipients":["a@b.it"],"note":"nota"}""", r)
        // null = valore predefinito del server: il campo non si manda
        assertEquals("{}", ApiJson.encodeToString(RichiestaContestazione.serializer(), RichiestaContestazione()))
        // lista vuota = solo il link, senza mail: si manda
        assertEquals("""{"recipients":[]}""", ApiJson.encodeToString(RichiestaContestazione.serializer(), RichiestaContestazione(recipients = emptyList())))
        val p = ApiJson.encodeToString(ModificaProfilo.serializer(), ModificaProfilo("Mario Rossi", "012345", listOf("a@b.it")))
        assertEquals("""{"full_name":"Mario Rossi","employee_number":"012345","contest_recipients":["a@b.it"]}""", p)
        val conEmail = ApiJson.encodeToString(ModificaProfilo.serializer(), ModificaProfilo("Mario Rossi", "012345", emptyList(), "mario@b.it"))
        assertEquals("""{"full_name":"Mario Rossi","employee_number":"012345","contest_recipients":[],"email":"mario@b.it"}""", conEmail)
    }

    @Test
    fun `chi sono e amministrazione letti dai modelli`() {
        val dati = esempi("risposte.json")
        val io = ApiJson.decodeFromJsonElement(Io.serializer(), dati["me"]!!)
        assertTrue(io.admin)
        assertEquals("bianchi", io.username)
        assertEquals(36, io.telefonoId.length)
        assertEquals("Mario Rossi", io.profile.nome)
        assertEquals("mario.rossi@example.it", io.profile.email)
        assertEquals(LocalDate.of(2026, 1, 1), io.assignments.single().validoDal)
        assertEquals("36 h su 5 giorni", io.assignments.single().nomeArticolazione)

        val utenti = ApiJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(UtenteSistema.serializer()), dati["system_users"]!!)
        val u = utenti.single()
        assertTrue(u.admin)
        assertEquals("012345", u.profile.matricola)
        assertNull(u.profile.email)
        assertTrue(u.devices.single().attivo)
        assertEquals(1L, u.assegnazione(LocalDate.of(2026, 11, 5))!!.articolazioneId)

        val articolazioni = ApiJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Articolazione.serializer()), dati["system_schedules"]!!)
        val a = articolazioni.single()
        assertEquals("36 h su 5 giorni", a.name)
        assertEquals(240, a.inVigore(LocalDate.of(2026, 11, 6))!!.days[5]!!.dovutiMin)
        // l'orario risolto dalle assegnazioni dell'esempio è quello che il server manda all'app (chiave schedule)
        val orario = it.zawardo.timbro.core.Orario.JSON.decodeFromJsonElement(it.zawardo.timbro.core.Orario.serializer(), dati["schedule"]!!)
        assertEquals(orario.versions, Amministrazione.risolvi(io.assignments, articolazioni).versions)

        val codice = ApiJson.decodeFromJsonElement(CodiceAssociazione.serializer(), dati["pairing_code"]!!)
        assertEquals(15, codice.minuti)
        assertEquals("rossi", codice.username)
    }

    @Test
    fun `richieste dell'amministratore con i nomi dei campi del backend`() {
        val j = Amministrazione.JSON
        assertEquals(
            """{"username":"rossi","role":"user","full_name":"Anna Rossi","employee_number":"42"}""",
            j.encodeToString(NuovoUtente.serializer(), NuovoUtente("rossi", nome = "Anna Rossi", matricola = "42")),
        )
        assertEquals("""{"username":"rossi","role":"admin"}""", j.encodeToString(NuovoUtente.serializer(), NuovoUtente("rossi", "admin")))
        assertEquals("""{"role":"admin"}""", j.encodeToString(CambioRuolo.serializer(), CambioRuolo("admin")))
        assertEquals("""{"name":"4 giorni da 9 ore"}""", j.encodeToString(NuovoNome.serializer(), NuovoNome("4 giorni da 9 ore")))
        val assegnazioni = listOf(RichiestaAssegnazione(1, LocalDate.of(2026, 1, 1)), RichiestaAssegnazione(2, LocalDate.of(2026, 9, 1)))
        assertEquals(
            """[{"schedule_id":1,"valid_from":"2026-01-01"},{"schedule_id":2,"valid_from":"2026-09-01"}]""",
            j.encodeToString(kotlinx.serialization.builtins.ListSerializer(RichiestaAssegnazione.serializer()), assegnazioni),
        )
        // nuova articolazione: la versione con tutti i campi dell'esempio del server, negli stessi formati
        val dati = esempi("risposte.json")
        val esempio = dati["system_schedules"]!!.jsonArray[0].jsonObject["versions"]!!.jsonArray[0].jsonObject
        val versione = j.decodeFromJsonElement(it.zawardo.timbro.core.VersioneOrario.serializer(), esempio)
        val corpo = Json.parseToJsonElement(j.encodeToString(NuovaArticolazione.serializer(), NuovaArticolazione("36 h su 5 giorni", versione))).jsonObject
        assertEquals(setOf("name", "version"), corpo.keys)
        assertEquals(esempio, corpo["version"])
        // il corpo di PUT /schedules/{id}/versions è la versione stessa
        assertEquals(esempio, Json.parseToJsonElement(j.encodeToString(it.zawardo.timbro.core.VersioneOrario.serializer(), versione)))
    }

    @Test
    fun `associazione con certificato letta e richiesta con i nomi del backend`() {
        val esito = ApiJson.decodeFromJsonElement(EsitoAssociazione.serializer(), esempi("risposte.json")["enroll"]!!)
        assertEquals(36, esito.telefonoId.length)
        assertTrue(esito.certificato!!.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(2, it.zawardo.timbro.core.Pem.blocchi(esito.catena!!).size)  // telefono + intermedia
        // /devices/register: niente certificato
        val register = ApiJson.decodeFromJsonElement(EsitoAssociazione.serializer(), esempi("risposte.json")["register"]!!)
        assertNull(register.catena)

        val richiesta = RichiestaAssociazione(RichiestaAssociazione.pulisciCodice("  AbC-d_9 "), "Pixel", "P1", "P2", csr = "CSR")
        val json = ApiJson.encodeToJsonElement(RichiestaAssociazione.serializer(), richiesta).jsonObject
        // i nomi di EnrollIn (timbro/api/app_api.py); l'attestazione assente non si manda
        assertEquals(setOf("pairing_code", "name", "public_key_pem", "admin_public_key_pem", "csr_pem"), json.keys)
        assertEquals("AbC-d_9", json["pairing_code"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { RichiestaAssociazione("", "Pixel", "a", "b") }
        assertThrows(IllegalArgumentException::class.java) { RichiestaAssociazione("x", "n".repeat(101), "a", "b") }
    }

    @Test
    fun `campi nuovi del server ignorati`() {
        val json = """{"days":[],"next_until":null,"campo_futuro":1}"""
        assertTrue(ApiJson.decodeFromString(Storico.serializer(), json).days.isEmpty())
    }

    @Test
    fun `query dello storico`() {
        assertEquals("days=14", Storico.query(null))
        assertEquals("until=2026-10-15&days=7", Storico.query(LocalDate.of(2026, 10, 15), 7))
        assertEquals("until=2026-10-15&days=62", Storico.query(LocalDate.of(2026, 10, 15), 500))
    }
}

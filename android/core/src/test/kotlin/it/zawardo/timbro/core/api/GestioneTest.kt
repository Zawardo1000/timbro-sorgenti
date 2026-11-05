package it.zawardo.timbro.core.api

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le risposte dell'API di gestione, nella forma di `timbro/api/admin_api.py` (_punch_summary, _share_summary). */
class GestioneTest {

    private val dettaglio = """
        {"id": 124, "client_id": "0f8fad5b-d9cb-469f-a165-70867728950e", "kind": "out",
         "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
         "server_received_at": "2026-11-05T11:31:02.481233+00:00",
         "chain_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752", "video_received": true,
         "stamps": [{"target": "video", "provider": "accv", "qualified": true, "status": "complete", "gen_time": "2026-11-05T11:31:04+00:00"}],
         "location": {"lat": 41.9, "lon": 12.5, "accuracy_m": 8.0, "provider": "fused", "is_mock": false, "fix_time": null},
         "attestation": {"ok": false, "problems": ["chiave non protetta dall'hardware (software)"]},
         "keep": true, "contested": false, "justification_ref": "Prot. 12", "notes": null,
         "device_time": "2026-11-05T12:31:00.000+01:00", "video_available": true,
         "video_deleted_at": null, "video_deleted_reason": null,
         "attestation_detail": {"ok": false, "problems": ["chiave non protetta dall'hardware (software)"], "root": null,
            "security_level": "software", "device_locked": true, "verified_boot_state": "verified", "os_version": 160000,
            "os_patch_level": 202610, "packages": [["it.zawardo.timbro.debug", 29]], "signature_digests": [],
            "serials": ["1"], "challenge_ok": true, "provisioning": null, "revoked": [], "notes": []},
         "wifi": {"reti": 2, "gia_viste_in_altre_timbrature": 1, "scartate_mac_casuali": 0,
            "dettaglio": [{"bssid": "a4:2b:b0:11:22:00", "rssi": -48, "mhz": 2437, "viste_in_altre_timbrature": 3},
                          {"bssid": "a4:2b:b0:11:22:01", "rssi": -51, "mhz": 5180, "viste_in_altre_timbrature": 0}]},
         "notices": [{"id": 3, "status": "sent", "recipients": ["a@b.it"], "subject": "Timbrature del 05/11/2026",
                      "created_at": "2026-11-05T11:40:00+00:00", "sent_at": "2026-11-05T11:40:05+00:00", "error": null,
                      "share_id": 7, "day": "2026-11-05", "punch_id": 124}]}
    """.trimIndent()

    @Test
    fun `dettaglio con posizione, reti, attestazione e contestazioni`() {
        val d = ApiJson.decodeFromString(DettaglioTimbratura.serializer(), dettaglio)
        assertEquals(8.0, d.location!!.precisioneM!!, 0.0)
        assertEquals(listOf("it.zawardo.timbro.debug (versione 29)"), d.attestazione!!.app)
        assertEquals("software", d.attestazione!!.livelloLeggibile)
        assertEquals(2, d.wifi!!.reti)
        assertEquals(3, d.wifi!!.dettaglio[0].visteAltrove)
        assertEquals(StatoAvviso.INVIATA, d.notices.single().stato)
        assertEquals("Prot. 12", d.giustificativo)
        assertFalse(Gestione.videoCancellabile(d))  // da tenere
        assertTrue(Gestione.videoCancellabile(d.copy(keep = false)))
        assertFalse(Gestione.videoCancellabile(d.copy(keep = false, videoCancellatoIl = "2026-11-06T00:00:00+00:00")))
    }

    @Test
    fun `risposta di PATCH e DELETE senza i campi del dettaglio`() {
        val corta = Json.parseToJsonElement(dettaglio).jsonObject.filterKeys { it !in setOf("attestation_detail", "wifi", "notices") }
        val d = ApiJson.decodeFromString(DettaglioTimbratura.serializer(), kotlinx.serialization.json.JsonObject(corta).toString())
        assertNull(d.attestazione)
        assertNull(d.wifi)
        assertTrue(d.notices.isEmpty())
    }

    @Test
    fun `PATCH manda solo i campi cambiati`() {
        val json = Json { explicitNulls = false; encodeDefaults = true }
        assertEquals("""{"keep":true}""", json.encodeToString(ModificaTimbratura.serializer(), ModificaTimbratura(keep = true)))
        assertEquals(
            """{"justification_ref":"","notes":"x"}""",
            json.encodeToString(ModificaTimbratura.serializer(), ModificaTimbratura(giustificativo = "", notes = "x")),
        )
    }

    @Test
    fun `link, accessi, ticket e impostazioni`() {
        val links = ApiJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LinkCondivisione.serializer()),
            """[{"id": 31, "label": "Per l'avvocato", "punch_ids": [121, 124], "allow_evidence_download": true,
                 "created_at": "2026-11-05T11:40:00+00:00", "expires_at": "2026-11-20T11:40:00+00:00", "revoked_at": null,
                 "active": true, "accesses": 4}]""",
        )
        assertEquals(4, links.single().accesses)
        assertNull(links.single().url)
        val accessi = ApiJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(AccessoLink.serializer()),
            """[{"id": 1, "at": "2026-11-05T12:00:00+00:00", "ip": "1.2.3.4", "user_agent": "Firefox", "what": "page"},
                {"id": 2, "at": "2026-11-05T12:01:00+00:00", "ip": null, "user_agent": null, "what": "evidence:124"}]""",
        )
        assertEquals("pagina", accessi[0].descrizione)
        assertEquals("pacchetto di prova della timbratura 124", accessi[1].descrizione)
        val ticket = ApiJson.decodeFromString(TicketVideo.serializer(), """{"url": "/api/v1/media/abc", "expires_in": 300}""")
        assertEquals("/api/v1/media/abc", ticket.url)
        val imp = ApiJson.decodeFromString(Impostazioni.serializer(), """{"retention_days": 90, "share_default_days": 15, "share_max_days": 60}""")
        assertEquals(90, imp.conservazioneGiorni)
        val json = Json { explicitNulls = false; encodeDefaults = true }
        assertEquals(
            """{"punch_ids":[121,124],"label":"Per l'avvocato","days":15,"allow_evidence_download":false}""",
            json.encodeToString(RichiestaLink.serializer(), RichiestaLink(listOf(121, 124), "Per l'avvocato", 15, download = false)),
        )
    }

    @Test
    fun `la coda conta finché il server non ha la timbratura`() {
        val server = listOf("a", "b")
        val coda = listOf("b", "c")
        assertEquals(listOf("c"), Unione.soloInCoda(server, coda, { it }, { it }))
    }

    @Test
    fun `ora della timbratura nel fuso del telefono`() {
        val v = VoceStorico(
            1, "x", "in", "2026-11-04T07:58:12+01:00", "2026-11-04T06:58:14+00:00", "ricevuto", true, null, false, false,
        )
        assertEquals(LocalDateTime.of(2026, 11, 4, 7, 58, 12), v.quando(ZoneId.of("Europe/Rome")))
        assertEquals(LocalDateTime.of(2026, 11, 4, 6, 58, 12), v.quando(ZoneId.of("UTC")))
    }
}

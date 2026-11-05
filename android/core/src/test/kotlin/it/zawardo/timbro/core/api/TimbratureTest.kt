package it.zawardo.timbro.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invio della coda: `POST /api/v1/punches` e stato delle prove per la catena di Timbra. */
class TimbratureTest {

    @Test
    fun `richiesta con i nomi del server e senza attestazione nulla`() {
        val conCatena = ApiJson.encodeToString(RichiestaTimbratura.serializer(), RichiestaTimbratura("bWFu", "ZmlybWE=", listOf("Zm9nbGlh", "cmFkaWNl")))
        assertEquals(setOf("manifest", "signature", "attestation"), Json.parseToJsonElement(conCatena).jsonObject.keys)
        // un telefono che non sa attestare: il campo manca (il server lo segnala come "attestazione mancante")
        val senza = ApiJson.encodeToString(RichiestaTimbratura.serializer(), RichiestaTimbratura("bWFu", "ZmlybWE="))
        assertEquals(setOf("manifest", "signature"), Json.parseToJsonElement(senza).jsonObject.keys)
    }

    private fun stato(marche: String, attestazione: String = "null") = ApiJson.decodeFromString(
        StatoTimbratura.serializer(),
        """{"id": 7, "client_id": "0f8fad5b-d9cb-469f-a165-70867728950e", "kind": "in", "sha256": "${"a".repeat(64)}",
            "server_received_at": "2026-11-05T11:31:02+00:00", "chain_hash": "${"b".repeat(64)}", "video_received": true,
            "stamps": [$marche], "location": null, "attestation": $attestazione}""",
    )

    @Test
    fun `marca qualificata e Bitcoin dal solo video`() {
        val accv = """{"target": "video", "provider": "accv", "qualified": true, "status": "complete", "gen_time": "2026-11-05T11:31:04+00:00"}"""
        val otsAttesa = """{"target": "video", "provider": "ots", "qualified": false, "status": "upgrading", "gen_time": null}"""
        val otsRecord = """{"target": "record", "provider": "ots", "qualified": false, "status": "complete", "gen_time": "2026-11-05T15:00:00+00:00"}"""
        val s = stato("$accv, $otsAttesa, $otsRecord")
        assertTrue(s.marcataQualificata)
        assertFalse(s.confermataSuBitcoin)  // conta la prova del video, non quella del record
        val confermata = stato("$accv, ${otsAttesa.replace("upgrading", "complete")}")
        assertTrue(confermata.confermataSuBitcoin)
        // marca non qualificata o qualificata ma sul record: il video non è ancora marcato
        val digicert = """{"target": "video", "provider": "digicert", "qualified": false, "status": "complete", "gen_time": null}"""
        assertFalse(stato("$digicert, ${accv.replace("\"video\"", "\"record\"")}").marcataQualificata)
    }

    @Test
    fun `esito dell attestazione`() {
        val s = stato("", """{"ok": false, "problems": ["chiave non protetta dall'hardware (software)"]}""")
        assertEquals(false, s.attestation?.ok)
        assertEquals(1, s.attestation?.problems?.size)
    }
}

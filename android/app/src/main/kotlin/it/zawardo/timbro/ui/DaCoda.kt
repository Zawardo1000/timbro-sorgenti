package it.zawardo.timbro.ui

import it.zawardo.timbro.core.Manifest
import it.zawardo.timbro.core.StatoInvio as StatoCoda
import it.zawardo.timbro.core.api.ApiJson
import it.zawardo.timbro.core.api.VoceStorico
import it.zawardo.timbro.core.api.quando
import it.zawardo.timbro.dati.coda.TimbraturaInCoda
import it.zawardo.timbro.dati.coda.VoceInCache
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private val ROMA: ZoneId = ZoneId.of("Europe/Rome")

/** Giorno della timbratura della coda in ora italiana (come lo raggruppa lo storico del server). */
fun TimbraturaInCoda.giorno(): LocalDate = Instant.ofEpochMilli(creataIl).atZone(ROMA).toLocalDate()

/**
 * Una timbratura della coda del telefono come la mostra l'interfaccia finché il server non la restituisce nello
 * storico: ora (inizio della ripresa), tipo, stato delle prove e i dati del manifest firmato (posizione e reti), che
 * l'app rilegge dai byte salvati. Id negativo: non è (ancora) una timbratura del server.
 */
fun TimbraturaInCoda.perInterfaccia(): Timbratura {
    val m = runCatching { ApiJson.decodeFromString(Manifest.serializer(), manifest.toString(Charsets.UTF_8)) }.getOrNull()
    return Timbratura(
        id = -id.toInt(),
        quando = LocalDateTime.ofInstant(Instant.ofEpochMilli(creataIl), ZoneId.systemDefault()).withNano(0),
        tipo = Tipo.da(kind),
        stato = when {
            serverId == null -> StatoInvio.IN_INVIO
            marcata -> StatoInvio.MARCATA
            else -> StatoInvio.RICEVUTA
        },
        videoRicevuto = videoRicevuto,
        attestazioneOk = integro,
        retiWifi = m?.wifi?.aps?.size ?: 0,
        retiGiaViste = 0,
        precisioneGpsM = m?.location?.accuracyM?.roundToInt() ?: 0,
        catena = catena(),
        clientId = clientId,
    )
}

private fun TimbraturaInCoda.catena() = CatenaProve(
    inviata = serverId != null,
    marcata = marcata,
    video = videoRicevuto,
    integro = integro,
    conAttestazione = attestazione != null,
    bitcoin = bitcoin,
    rifiutata = stato == StatoCoda.RIFIUTATA,
    errore = ultimoErrore,
    problemi = problemiAttestazione,
)

/** Una voce di `GET /history` per la cache (giorno in ora italiana, come la raggruppa il server). */
fun VoceStorico.perCache(giorno: LocalDate) = VoceInCache(
    id, clientId, kind, time, ricevutaIl, giorno.toString(), video, marcataQualificata, attestazioneOk, contested, keep,
)

/**
 * Una timbratura dello storico del server. Se è di questo telefono ([riga] della coda, stesso `client_id`) le prove
 * lette dal worker (Bitcoin, problemi dell'attestazione) completano quelle dello storico.
 */
fun VoceInCache.perInterfaccia(riga: TimbraturaInCoda?): Timbratura {
    val voce = VoceStorico(id, clientId, kind, time, ricevutaIl, video, marcata, attestazioneOk, contestata, daTenere)
    val marcata = marcata || riga?.marcata == true
    val ricevuto = video == "ricevuto" || (video != "cancellato" && riga?.videoRicevuto == true)
    val integro = attestazioneOk ?: riga?.integro
    return Timbratura(
        id = id.toInt(),
        quando = voce.quando(ZoneId.systemDefault()).withNano(0),
        tipo = Tipo.da(kind),
        stato = if (marcata) StatoInvio.MARCATA else StatoInvio.RICEVUTA,
        contestata = contestata,
        daTenere = daTenere,
        videoEliminato = video == "cancellato",
        videoRicevuto = ricevuto,
        attestazioneOk = integro,
        retiWifi = 0,
        retiGiaViste = 0,
        precisioneGpsM = 0,
        catena = CatenaProve(
            inviata = true,
            marcata = marcata,
            video = ricevuto || video == "cancellato",
            integro = integro,
            conAttestazione = attestazioneOk != null || riga?.attestazione != null,
            bitcoin = riga?.bitcoin == true,
            rifiutata = false,
            errore = null,
            problemi = riga?.problemiAttestazione,
        ),
        clientId = clientId,
        ricevutaIl = ricevutaIl,
    )
}

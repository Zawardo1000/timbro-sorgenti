package it.zawardo.timbro.ui

import androidx.compose.ui.graphics.Color
import it.zawardo.timbro.core.Segno
import it.zawardo.timbro.core.api.StatoAvviso
import it.zawardo.timbro.ui.theme.Colori
import java.time.LocalDate
import java.time.LocalDateTime

enum class Tipo(val etichetta: String, val kind: String, val colore: Color) {
    ENTRATA("Entrata", "in", Colori.Entrata), USCITA("Uscita", "out", Colori.Uscita);

    val opposto: Tipo get() = if (this == ENTRATA) USCITA else ENTRATA

    companion object {
        fun da(kind: String?): Tipo = entries.firstOrNull { it.kind == kind } ?: ENTRATA
    }
}

/**
 * [IN_INVIO]: ancora solo sul telefono (coda), bordo tratteggiato nello Storico; [RICEVUTA]: il server ce l'ha, la
 * marca qualificata sul video non è ancora arrivata; [MARCATA]: marca qualificata completata.
 */
enum class StatoInvio { IN_INVIO, RICEVUTA, MARCATA }

/**
 * Stato delle prove di una timbratura, per la catena di Timbra: firmata (sempre), marcata (marca qualificata sul
 * video), video ricevuto, integro (attestazione hardware), Bitcoin. [integro] null = non ancora noto;
 * [conAttestazione] false = il telefono non ha saputo attestare.
 */
data class CatenaProve(
    val inviata: Boolean,
    val marcata: Boolean,
    val video: Boolean,
    val integro: Boolean?,
    val conAttestazione: Boolean,
    val bitcoin: Boolean,
    val rifiutata: Boolean,
    val errore: String?,
    val problemi: String?,
)

/**
 * Una timbratura come la vede l'app: dallo storico del server (in cache per aprire senza rete) o dalla coda del
 * telefono finché il server non la restituisce (stesso `client_id`). [id] è l'id del server; quelle solo in coda
 * hanno l'id negativo della coda. Posizione, reti, giustificativo e note non stanno qui: arrivano dal dettaglio,
 * con l'impronta (tranne che nella modalità dimostrativa).
 */
data class Timbratura(
    val id: Int,
    override val quando: LocalDateTime,
    val tipo: Tipo,
    val stato: StatoInvio = StatoInvio.MARCATA,
    val contestata: Boolean = false,
    val daTenere: Boolean = false,
    val videoEliminato: Boolean = false,
    /** Il server ha il video (false finché non arriva, e dopo la cancellazione). */
    val videoRicevuto: Boolean = true,
    val giustificativo: String = "",
    val note: String = "",
    /** Esito dell'attestazione hardware; null = senza attestazione o non ancora verificata. */
    val attestazioneOk: Boolean? = true,
    val retiWifi: Int = 4,
    val retiGiaViste: Int = 4,
    val precisioneGpsM: Int = 12,
    val catena: CatenaProve? = null,
    val clientId: String? = null,
    /** Ricezione sul server (ISO 8601 con fuso), dallo storico. */
    val ricevutaIl: String? = null,
) : Segno {
    override val kind: String get() = tipo.kind

    /** Sul server: si può gestire (flag, link, contestazioni). */
    val sulServer: Boolean get() = id > 0

    val serverId: Long? get() = id.takeIf { it > 0 }?.toLong()
}

/** Link appena creato: l'indirizzo col token si vede solo ora (il server ne tiene l'impronta), quindi sta nel ViewModel. */
data class NuovoLink(val indirizzo: String, val scade: LocalDate)

/**
 * Contestazione mandata (in questa sessione) o letta dal dettaglio di una timbratura (`notices`). [timbraturaId] null
 * = segnalazione di tutta la giornata [giorno].
 */
data class ContestazioneInviata(
    val id: Long,
    val timbraturaId: Int?,
    val giorno: LocalDate,
    val creata: LocalDateTime,
    val destinatari: List<String>,
    /** Scadenza del link (null se non nota: le mail lette dal dettaglio non la riportano). */
    val scade: LocalDate?,
    val stato: StatoAvviso,
    val inviataAlle: LocalDateTime? = null,
    val nota: String? = null,
    val errore: String? = null,
) {
    val conMail: Boolean get() = destinatari.isNotEmpty()
}

/** Esito appena ottenuto: l'indirizzo col token si vede solo ora, come per i link (sta nel ViewModel). */
data class EsitoContestazioneProva(val contestazioneId: Long, val indirizzo: String)

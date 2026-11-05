package it.zawardo.timbro.ui

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.acquisizione.cartellaVideo
import it.zawardo.timbro.dati.coda.Invio
import it.zawardo.timbro.sveglia.SvegliaRientro
import java.io.File
import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.api.Io
import it.zawardo.timbro.core.api.RichiestaAssociazione
import it.zawardo.timbro.dati.ErroreHttp
import it.zawardo.timbro.dati.FirmatarioAdmin
import it.zawardo.timbro.sicurezza.Ambiente
import it.zawardo.timbro.sicurezza.AutenticazioneAnnullata
import it.zawardo.timbro.sicurezza.Chiavi
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Il telefono e il server (docs/PROGETTO.md §22): associazione al primo avvio, chi sono (`/me`), il mio orario
 * (`/schedule`), che passano a [StatoApp].
 */
class Collegamento(app: Application) : AndroidViewModel(app) {
    private val timbro = app as TimbroApp
    private val associazione = timbro.associazione
    private val chiavi = timbro.chiavi
    private val api = timbro.api

    var associato by mutableStateOf(associazione.associato)
        private set
    var lavoro by mutableStateOf(false)
        private set
    var errore by mutableStateOf<String?>(null)
        private set
    var messaggio by mutableStateOf<String?>(null)
        private set

    var io by mutableStateOf<Io?>(null)
        private set
    var orario by mutableStateOf<Orario?>(null)
        private set

    val ambiente: Ambiente get() = associazione.ambiente
    val telefonoId: String? get() = associazione.telefonoId
    val nomeTelefono: String? get() = associazione.nomeTelefono
    val conCertificato: Boolean get() = associazione.conCertificato
    fun livelloChiavi(): String = if (associato) chiavi.livello(Chiavi.TIMBRATURE) else "—"

    init {
        if (associato) aggiorna()
    }

    fun cambiaAmbiente(a: Ambiente) {
        associazione.ambiente = a
    }

    /**
     * Crea le chiavi nel chip, manda chiavi pubbliche e CSR con il codice, installa il certificato firmato dal server.
     * La challenge dell'attestazione della chiave delle timbrature è l'impronta del codice: lega l'attestazione a
     * questa associazione.
     */
    fun associa(codiceScritto: String, nome: String) {
        if (lavoro) return
        errore = null
        lavoro = true
        viewModelScope.launch {
            try {
                val codice = RichiestaAssociazione.pulisciCodice(codiceScritto)
                val richiesta = withContext(Dispatchers.Default) {
                    chiavi.crea(MessageDigest.getInstance("SHA-256").digest(codice.toByteArray()))
                    RichiestaAssociazione(
                        codice, nome.trim(),
                        chiavi.pubblicaPem(Chiavi.TIMBRATURE), chiavi.pubblicaPem(Chiavi.ADMIN),
                        attestazione = chiavi.attestazionePem(Chiavi.TIMBRATURE),
                        csr = chiavi.csrTls(nome.trim()),
                    )
                }
                val esito = api.associa(richiesta)
                withContext(Dispatchers.Default) { esito.catena?.let { chiavi.installaCatenaTls(it) } }
                associazione.salva(esito.telefonoId, esito.token, nome.trim(), conCertificato = esito.catena != null)
                associato = true
                aggiorna()
            } catch (e: Exception) {
                chiavi.cancella()
                errore = spiega(e, associando = true)
            } finally {
                lavoro = false
            }
        }
    }

    private var aggiornando = false

    /** `/me` e `/schedule` dal server (una lettura alla volta). */
    fun aggiorna() {
        if (aggiornando) return
        aggiornando = true
        viewModelScope.launch {
            try {
                io = api.io()
                orario = api.orario()
                errore = null
            } catch (e: Exception) {
                errore = spiega(e, associando = false)
            } finally {
                aggiornando = false
            }
        }
    }

    /** Una chiamata di gestione firmata con l'impronta (`GET /admin/profile`): prova chiave admin e firma. */
    fun provaAdmin(firmatario: FirmatarioAdmin) {
        viewModelScope.launch {
            try {
                val p = api.profilo(firmatario)
                messaggio = "Firma admin accettata dal server. Profilo: ${p.nome ?: "senza nome"}${p.matricola?.let { ", matricola $it" } ?: ""}."
            } catch (e: Exception) {
                messaggio = spiega(e, associando = false)
            }
        }
    }

    fun chiudiMessaggio() {
        messaggio = null
    }

    /**
     * Dissocia il telefono: toglie tutto quello che lo lega all'utente (docs/PROGETTO.md §20). Prima gli invii
     * programmati, poi token, id e chiavi del Keystore (senza, niente può più partire), la sveglia del rientro e le
     * notifiche, la coda e lo storico locali (Room), i video rimasti, le preferenze dell'app. Sul server il telefono
     * resta registrato finché l'amministratore non lo revoca. [fatto] riapre l'app da capo: senza associazione
     * mostra la schermata del codice, e nessun dato in memoria della sessione di prima sopravvive.
     */
    fun dissocia(fatto: () -> Unit) {
        if (lavoro) return
        lavoro = true
        errore = null
        messaggio = null
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                Invio.fermaPer(ctx) {
                    withContext(Dispatchers.IO) {
                        associazione.dissocia()
                        SvegliaRientro(ctx).annulla()
                        NotificationManagerCompat.from(ctx).cancelAll()
                        timbro.coda.clearAllTables()
                        cartellaVideo(ctx).deleteRecursively()
                        listOf("video", "prove").forEach { File(ctx.cacheDir, it).deleteRecursively() }
                        for (nome in listOf("stato_app", "sveglia")) ctx.getSharedPreferences(nome, Context.MODE_PRIVATE).edit().clear().commit()
                    }
                }
                io = null
                orario = null
                associato = false
                fatto()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                errore = "Dissociazione non completata: ${e.message ?: e.javaClass.simpleName}. Riprova."
            } finally {
                lavoro = false
            }
        }
    }

    private fun spiega(e: Exception, associando: Boolean): String = when (e) {
        is ErroreHttp -> when {
            associando && e.codice == 403 -> "Codice sbagliato, già usato o scaduto: chiedine uno nuovo all'amministratore."
            associando && e.codice == 503 -> "Il server non può ancora rilasciare i certificati dei telefoni: avvisa l'amministratore."
            e.codice == 401 -> "Il server non riconosce questo telefono (revocato?)."
            e.codice == 403 -> e.dettaglio ?: "Operazione non permessa."
            e.codice == 422 -> "Il server ha rifiutato i dati: ${e.dettaglio ?: "richiesta non valida"}."
            else -> "Il server ha risposto ${e.codice}${e.dettaglio?.let { ": $it" } ?: ""}."
        }
        is AutenticazioneAnnullata -> "Impronta non confermata: ${e.message}"
        is IOException -> "Server non raggiungibile (${ambiente.apiBase}): ${e.message ?: e.javaClass.simpleName}"
        is java.security.InvalidAlgorithmParameterException, is IllegalStateException ->
            if (associando) "Serve un blocco schermo (PIN o impronta) per proteggere le chiavi: impostalo e riprova." else (e.message ?: "errore")
        else -> e.message ?: e.javaClass.simpleName
    }
}

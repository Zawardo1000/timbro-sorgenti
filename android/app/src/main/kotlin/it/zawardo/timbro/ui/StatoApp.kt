package it.zawardo.timbro.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.timbro.BuildConfig
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.acquisizione.Ripresa
import it.zawardo.timbro.core.EsitoOre
import it.zawardo.timbro.core.Giornata
import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.Proposta
import it.zawardo.timbro.core.Ricerca
import it.zawardo.timbro.core.StatoInvio as StatoCoda
import it.zawardo.timbro.core.Sveglia
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.core.api.AccessoLink
import it.zawardo.timbro.core.api.ApiJson
import it.zawardo.timbro.core.api.Articolazione
import it.zawardo.timbro.core.api.Assegnazione
import it.zawardo.timbro.core.api.AvvisoMail
import it.zawardo.timbro.core.api.CodiceAssociazione
import it.zawardo.timbro.core.api.DettaglioAttestazione
import it.zawardo.timbro.core.api.DettaglioTimbratura
import it.zawardo.timbro.core.api.EsitoAttestazione
import it.zawardo.timbro.core.api.Io
import it.zawardo.timbro.core.api.LinkCondivisione
import it.zawardo.timbro.core.api.Marca
import it.zawardo.timbro.core.api.ModificaProfilo
import it.zawardo.timbro.core.api.ModificaTimbratura
import it.zawardo.timbro.core.api.NuovaArticolazione
import it.zawardo.timbro.core.api.NuovoUtente
import it.zawardo.timbro.core.api.PosizioneRegistrata
import it.zawardo.timbro.core.api.Profilo
import it.zawardo.timbro.core.api.ReteVistaServer
import it.zawardo.timbro.core.api.RichiestaAssegnazione
import it.zawardo.timbro.core.api.RichiestaContestazione
import it.zawardo.timbro.core.api.RichiestaLink
import it.zawardo.timbro.core.api.RiepilogoWifi
import it.zawardo.timbro.core.api.StatoAvviso
import it.zawardo.timbro.core.api.StatoTimbratura
import it.zawardo.timbro.core.api.Unione
import it.zawardo.timbro.core.api.UtenteSistema
import it.zawardo.timbro.dati.FirmatarioAdmin
import it.zawardo.timbro.dati.StatoRete
import it.zawardo.timbro.dati.coda.TimbraturaInCoda
import it.zawardo.timbro.dati.coda.VoceInCache
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * Lo stato dell'app con i dati veri del server (docs/PROGETTO.md §20).
 *
 * - Storico: `GET /api/v1/history` (solo token) unito alla coda del telefono per `client_id`; l'ultima lettura sta in
 *   Room (tabella `storico`) e l'app si apre anche senza rete.
 * - Gestione e dati sensibili: API admin firmate con l'impronta ([FirmatarioAdmin]), **una impronta per chiamata**.
 *   Per questo i dati di gestione si leggono solo quando servono (dettaglio, link, utenti, articolazioni) e restano in
 *   memoria per la sessione; il profilo arriva già da `/me`.
 * - Modalità dimostrativa (solo debug): i dati finti di [Dimostrazione], senza server.
 */
class StatoApp(app: Application) : AndroidViewModel(app) {
    private val timbro = app as TimbroApp
    private val api get() = timbro.api
    private val dao get() = timbro.coda.storico()
    private val prefs = app.getSharedPreferences("stato_app", Context.MODE_PRIVATE)

    // --- modalità dimostrativa -----------------------------------------------------------------------------------

    var dimostrazione by mutableStateOf(BuildConfig.DEBUG && prefs.getBoolean(DIMOSTRAZIONE, false))
        private set

    fun cambiaDimostrazione(accesa: Boolean) {
        if (!BuildConfig.DEBUG || accesa == dimostrazione) return
        prefs.edit { putBoolean(DIMOSTRAZIONE, accesa) }
        dimostrazione = accesa
        azzeraSessione()
        if (accesa) caricaDimostrazione() else aggiornaStorico()
        ricomponi()
    }

    private var finte: List<Timbratura> = emptyList()

    private fun caricaDimostrazione() {
        finte = Dimostrazione.timbrature()
        utenti.addAll(Dimostrazione.utenti)
        utentiCaricati = true
        articolazioni.addAll(Dimostrazione.articolazioni)
        articolazioniCaricate = true
        link = Dimostrazione.link(LocalDate.now())
        conservazioneGiorni = 90
    }

    private fun azzeraSessione() {
        finte = emptyList()
        utenti.clear()
        utentiCaricati = false
        articolazioni.clear()
        articolazioniCaricate = false
        link = null
        nuovoLink = null
        conservazioneGiorni = null
        contestazioni.clear()
        avvisi.clear()
        avvisiLetti = false
        esitoContestazione = null
        dettagli.clear()
        profiloLetto = null
        erroreStorico = null
    }

    // --- timbrature: storico del server + coda ----------------------------------------------------------------------

    /** Dalla più recente. */
    var timbrature by mutableStateOf<List<Timbratura>>(emptyList())
        private set

    /** Registrazione finita e non ancora confermata (Registrazione → Conferma); il file sta nella cartella privata. */
    var ripresa by mutableStateOf<Ripresa?>(null)

    private var cache: List<VoceInCache> = emptyList()
    private var coda by mutableStateOf<List<TimbraturaInCoda>>(emptyList())

    /** Timbrature ancora da mandare (manifest o video): la pastiglia di Timbra le conta. */
    val inCoda: Int get() = coda.count { it.stato == StatoCoda.DA_INVIARE || it.stato == StatoCoda.VIDEO_DA_INVIARE }

    /** Dal giorno [caricatoDal] a oggi lo storico è in cache senza buchi; [altriPrima]: da lì si continua (null = finito). */
    var caricatoDal by mutableStateOf(prefs.getString(DAL, null)?.let(LocalDate::parse))
        private set
    var altriPrima by mutableStateOf(prefs.getString(PRIMA, null)?.let(LocalDate::parse))
        private set

    /** Giorni letti per la ricerca per data, fuori dall'intervallo continuo (solo per questa sessione). */
    private val finestre = mutableStateListOf<Ricerca.Finestra>()

    var caricamento by mutableStateOf(false)
        private set
    var erroreStorico by mutableStateOf<String?>(null)
        private set
    var aggiornatoIl by mutableStateOf<LocalDateTime?>(null)
        private set
    private var ancora = false
    private val giaRichieste = HashSet<String>()

    override fun onCleared() {
        File(getApplication<Application>().cacheDir, "video").deleteRecursively()
    }

    /** Dissociazione: si fermano letture e invii in corso, perché non scrivano più su cache e preferenze. */
    fun ferma() {
        viewModelScope.coroutineContext.cancelChildren()
    }

    /** La coda del telefono (Room, si aggiorna da sola): le timbrature in invio compaiono subito. */
    fun usaCoda(righe: List<TimbraturaInCoda>) {
        coda = righe
        ricomponi()
        if (dimostrazione) return
        // arrivate al server dopo l'ultima lettura dello storico: si rilegge (una volta per timbratura)
        val note = cache.mapTo(HashSet()) { it.clientId }
        val limite = oggiRoma().minusDays(GIORNI.toLong())
        val nuove = righe.filter { it.serverId != null && it.clientId !in note && it.clientId !in giaRichieste && !it.giorno().isBefore(limite) }
        if (nuove.isNotEmpty()) {
            giaRichieste += nuove.map { it.clientId }
            aggiornaStorico()
        }
    }

    private fun ricomponi() {
        if (dimostrazione) {
            // le timbrature vere della coda prendono il posto di quelle finte di oggi
            val vere = coda.map { it.perInterfaccia() }
            val oggi = LocalDate.now()
            timbrature = (finte.filter { vere.isEmpty() || it.quando.toLocalDate() != oggi } + vere).sortedByDescending { it.quando }
            return
        }
        val perClient = coda.associateBy { it.clientId }
        val dalServer = cache.map { it.perInterfaccia(perClient[it.clientId]) }
        val dal = caricatoDal
        // dalla coda solo quelle che il server non ha ancora restituito; quelle già inviate e più vecchie dello storico
        // letto restano fuori (le mostrerà il server quando si caricano quei giorni)
        val soloCoda = Unione.soloInCoda(cache, coda, { it.clientId }, { it.clientId })
            .filter { it.serverId == null || dal == null || !it.giorno().isBefore(dal) }
            .map { it.perInterfaccia() }
        timbrature = (dalServer + soloCoda).sortedByDescending { it.quando }
    }

    /** Ultimi [GIORNI] giorni dal server; all'apertura, al ritorno nell'app e quando una timbratura arriva al server. */
    fun aggiornaStorico() {
        if (dimostrazione) return
        if (caricamento) {
            ancora = true
            return
        }
        val oggi = oggiRoma()
        val f = Ricerca.Finestra(oggi, GIORNI)
        val ultimo = prefs.getString(FINO, null)?.let(LocalDate::parse)
        // c'è sovrapposizione con l'ultima lettura: l'intervallo continuo resta quello di prima
        val continuo = caricatoDal != null && ultimo != null && !ultimo.isBefore(f.dal.minusDays(1))
        carica(f) { s ->
            prefs.edit { putString(FINO, oggi.toString()) }
            if (!continuo || caricatoDal?.isAfter(f.dal) == true) segnaContinuo(f.dal, s.nextUntil)
        }
    }

    /** "Carica i giorni precedenti": la pagina prima dell'intervallo continuo. */
    fun caricaPrecedenti() {
        val prima = altriPrima ?: return
        if (dimostrazione || caricamento) return
        val f = Ricerca.Finestra(prima, GIORNI)
        carica(f) { s -> segnaContinuo(f.dal, s.nextUntil) }
    }

    /** Ricerca per data: i giorni intorno a [data], se non sono già in cache. */
    fun caricaGiorno(data: LocalDate) {
        if (dimostrazione || caricato(data) || caricamento) return
        val f = Ricerca.finestra(data, oggiRoma())
        carica(f) { finestre += f }
    }

    /** Lo storico di quel giorno è stato letto dal server (anche in una sessione precedente). */
    fun caricato(data: LocalDate): Boolean =
        dimostrazione || caricatoDal?.let { !data.isBefore(it) } == true || finestre.any { it.contiene(data) }

    /**
     * Timbrature della coda già accettate dal server prima della lettura, di giorni che la lettura copre, che il server
     * non restituisce più: le ha cancellate (azzeramento dei dati di prova, 06/11/2026). Senza questo la coda le teneva
     * per sempre e lo storico le mostrava come "non ancora restituite". Si tolgono dalla coda, con il video se c'è
     * ancora. Il primo giorno della finestra resta fuori: il server raggruppa per l'ora della timbratura, la coda per
     * l'ora del telefono, e al bordo potrebbero non coincidere.
     */
    private suspend fun dimenticaCancellate(f: Ricerca.Finestra, accettatePrima: Set<String>, restituite: Set<String>) {
        val codaDao = timbro.coda.coda()
        val sparite = codaDao.tutte().filter {
            it.serverId != null && it.clientId in accettatePrima && it.clientId !in restituite &&
                it.giorno() > f.dal && it.giorno() <= f.fino
        }
        if (sparite.isEmpty()) return
        sparite.forEach { File(it.percorsoVideo).delete() }
        codaDao.elimina(sparite.map { it.id })
    }

    private fun segnaContinuo(dal: LocalDate, prossimo: String?) {
        caricatoDal = dal
        altriPrima = prossimo?.let(LocalDate::parse)
        prefs.edit {
            putString(DAL, dal.toString())
            if (prossimo != null) putString(PRIMA, prossimo) else remove(PRIMA)
        }
    }

    private fun carica(f: Ricerca.Finestra, poi: (it.zawardo.timbro.core.api.Storico) -> Unit) {
        caricamento = true
        // accettate dal server prima di questa lettura: se il server non le restituisce, le ha cancellate
        val accettatePrima = coda.filter { it.serverId != null }.mapTo(HashSet()) { it.clientId }
        viewModelScope.launch {
            val esito = tenta {
                val s = api.storico(f.fino, f.giorni)
                val voci = s.days.flatMap { g -> g.punches.map { it.perCache(g.giorno) } }
                dao.sostituisci(f.dal.toString(), f.fino.toString(), voci)
                cache = dao.tutte()
                dimenticaCancellate(f, accettatePrima, voci.mapTo(HashSet()) { it.clientId })
                s
            }
            when (esito) {
                is Risultato.Ok -> {
                    erroreStorico = null
                    aggiornatoIl = LocalDateTime.now().withNano(0)
                    poi(esito.valore)
                }
                is Risultato.Errore -> erroreStorico = esito.messaggio
            }
            ricomponi()
            caricamento = false
            if (ancora) {
                ancora = false
                aggiornaStorico()
            }
        }
    }

    private suspend fun aggiornaVoce(id: Long, cambia: (VoceInCache) -> VoceInCache) {
        val v = cache.firstOrNull { it.id == id } ?: return
        val nuova = cambia(v)
        cache = cache.map { if (it.id == id) nuova else it }
        dao.aggiungi(listOf(nuova))
        ricomponi()
    }

    private fun aggiornaFinta(id: Int, cambia: (Timbratura) -> Timbratura) {
        finte = finte.map { if (it.id == id) cambia(it) else it }
        ricomponi()
    }

    fun timbratura(id: Int): Timbratura? = timbrature.firstOrNull { it.id == id }

    fun ultima(): Timbratura? = timbrature.firstOrNull()

    fun giornata(giorno: LocalDate): Giornata<Timbratura> = Giornata(giorno, timbrature)

    /** L'opposto dell'ultima timbratura di oggi; la prima del giorno è un'entrata (regola in :core, Proposta). */
    fun tipoProposto(oggi: LocalDate = LocalDate.now()): Tipo {
        val ultima = timbrature.maxByOrNull { it.quando }
        return Tipo.da(Proposta.tipo(ultima?.tipo?.kind, ultima?.quando?.toLocalDate(), oggi))
    }

    // --- io, orario, rete -------------------------------------------------------------------------------------------

    private var ioServer by mutableStateOf<Io?>(null)
    private var orarioServer by mutableStateOf<Orario?>(null)

    /** Chi sono (`GET /me`, in cache per aprire senza rete). */
    val io: Io? get() = if (dimostrazione) Dimostrazione.io else ioServer

    /** Dal Collegamento (`/me` e `/schedule` all'avvio e con "Aggiorna dal server"). */
    fun usaIo(nuovo: Io?) {
        if (nuovo == null) return
        ioServer = nuovo
        profiloLetto = null
        nuovo.mailConfigurata?.let { aggiornaMail(it) }  // senza impronta: Profilo e Contesta lo sanno subito
        salva(IO, Io.serializer(), nuovo)
    }

    fun usaOrario(nuovo: Orario?) {
        if (nuovo == null) return
        orarioServer = nuovo
        salva(ORARIO, Orario.serializer(), nuovo)
    }

    /** Dopo un cambio di assegnazioni o di articolazioni che mi riguarda: `/me` e `/schedule` di nuovo (solo token). */
    private suspend fun rileggiIo() {
        tenta {
            usaIo(api.io())
            usaOrario(api.orario())
        }
    }

    val ioId: Long? get() = io?.utenteId
    val questoTelefono: String? get() = io?.telefonoId

    /** Interruttore di prova: con false l'app si vede come la vede un utente normale (senza Amministrazione). */
    var vediComeAdmin by mutableStateOf(true)

    val admin: Boolean get() = vediComeAdmin && io?.admin == true

    /** Il mio orario risolto (`GET /schedule`); senza, nessuna regola: le ore sono quelle fra le timbrature. */
    val orario: Orario get() = if (dimostrazione) Dimostrazione.orario else orarioServer ?: Orario()

    fun assegnazioni(): List<Assegnazione> = io?.assignments.orEmpty()

    fun assegnazioneOggi(oggi: LocalDate): Assegnazione? = assegnazioni().filter { !it.validoDal.isAfter(oggi) }.maxByOrNull { it.validoDal }

    fun versione(giorno: LocalDate): VersioneOrario? = orario.inVigore(giorno)

    /** Ore valide del giorno con l'orario in vigore; oggi conta fino ad [adesso]. */
    fun ore(giorno: LocalDate, adesso: LocalDateTime? = null): EsitoOre =
        Ore.calcola(timbrature.filter { it.quando.toLocalDate() == giorno }, versione(giorno), adesso?.takeIf { it.toLocalDate() == giorno })

    /** Il giorno ha la pausa con il rientro; senza orario si fa come se l'avesse (2 entrate e 2 uscite). */
    fun conPausa(giorno: LocalDate): Boolean = versione(giorno)?.conPausa(giorno) ?: true

    /**
     * Un'uscita a quell'ora apre la pausa: giorno con la pausa e uscita nella fascia (12:15-13:15 con l'orario attuale).
     * Senza orario si fa come prima: la prima uscita è la pausa.
     */
    fun uscitaDiPausa(giorno: LocalDate, ora: LocalTime): Boolean = versione(giorno)?.uscitaDiPausa(giorno, ora) ?: true

    /** Sveglia del rientro accesa di default: prima uscita, fatta adesso nella fascia della pausa di un giorno che ce l'ha. */
    fun svegliaPredefinita(adesso: LocalDateTime): Boolean =
        Sveglia.predefinita(giornata(adesso.toLocalDate()).uscite, uscitaDiPausa(adesso.toLocalDate(), adesso.toLocalTime()))

    /** La pastiglia di Timbra: connettività del telefono ed esito dell'ultima chiamata al server. */
    fun statoRete(): StatoRete {
        val r = timbro.rete
        return when {
            !r.connesso -> StatoRete.OFFLINE
            r.serverRaggiungibile == false -> StatoRete.SERVER_GIU
            else -> StatoRete.ONLINE
        }
    }

    // --- dettaglio e gestione di una timbratura --------------------------------------------------------------------

    /** Dettagli letti con l'impronta in questa sessione (posizione, reti, note): solo in memoria. */
    val dettagli = mutableStateMapOf<Long, DettaglioTimbratura>()

    /**
     * Marche, video e attestazione di una timbratura di questo telefono (`GET /punches/{id}`, solo token). Quelle
     * fatte con un telefono precedente non si leggono così (404): bastano i dati dello storico o il dettaglio.
     */
    suspend fun statoProve(t: Timbratura): StatoTimbratura? {
        val id = t.serverId ?: return null
        if (dimostrazione || coda.none { it.clientId == t.clientId }) return null
        return (tenta { api.statoTimbratura(id) } as? Risultato.Ok)?.valore
    }

    /** `GET /admin/punches/{id}`: posizione, reti, note, attestazione completa e mail di contestazione (impronta). */
    suspend fun dettaglio(t: Timbratura, f: FirmatarioAdmin): Risultato<DettaglioTimbratura> {
        val id = t.serverId ?: return nonSulServer()
        if (dimostrazione) return Risultato.Ok(dettaglioFinto(t).also { dettagli[id] = it })
        return tenta { api.dettaglio(id, f).also { registra(it, completo = true) } }
    }

    /** Flag, giustificativo e note (`PATCH /admin/punches/{id}`). */
    suspend fun modifica(t: Timbratura, m: ModificaTimbratura, f: FirmatarioAdmin): Risultato<Unit> {
        val id = t.serverId ?: return nonSulServer()
        if (dimostrazione) {
            aggiornaFinta(t.id) {
                it.copy(
                    contestata = m.contested ?: it.contestata, daTenere = m.keep ?: it.daTenere,
                    giustificativo = m.giustificativo ?: it.giustificativo, note = m.notes ?: it.note,
                )
            }
            timbratura(t.id)?.let { dettagli[id] = dettaglioFinto(it) }
            return OK
        }
        return tenta { registra(api.modifica(id, m, f), completo = false) }
    }

    /** `DELETE /admin/punches/{id}/video`: restano impronta, firme e marche. */
    suspend fun eliminaVideo(t: Timbratura, f: FirmatarioAdmin): Risultato<Unit> {
        val id = t.serverId ?: return nonSulServer()
        if (dimostrazione) {
            aggiornaFinta(t.id) { it.copy(videoEliminato = true, videoRicevuto = false) }
            return OK
        }
        return tenta { registra(api.eliminaVideo(id, f), completo = false) }
    }

    /** Il video in un file della cache (ticket con l'impronta, poi download): si cancella uscendo dal dettaglio. */
    suspend fun scaricaVideo(t: Timbratura, f: FirmatarioAdmin): Risultato<File> {
        val id = t.serverId ?: return nonSulServer()
        if (dimostrazione) return NON_IN_PROVA
        return tenta { api.scaricaVideo(api.ticketVideo(id, f), File(cartella("video"), "timbratura-$id.mp4")) }
    }

    /** Il pacchetto di prova (zip) in un file della cache, da condividere con il selettore di Android. */
    suspend fun scaricaPacchetto(t: Timbratura, f: FirmatarioAdmin): Risultato<File> {
        val id = t.serverId ?: return nonSulServer()
        if (dimostrazione) return NON_IN_PROVA
        return tenta { api.scaricaPacchetto(id, File(cartella("prove"), "timbro-prova-$id.zip"), f) }
    }

    fun cancellaVideo(file: File?) {
        file?.delete()
    }

    private fun cartella(nome: String) = File(getApplication<Application>().cacheDir, nome).apply { mkdirs() }

    /**
     * Risposta di dettaglio, PATCH o DELETE: aggiorna la cache dello storico e, se il dettaglio è già stato letto,
     * quello in memoria (PATCH e DELETE non hanno i campi del solo dettaglio: si tengono quelli letti).
     */
    private suspend fun registra(d: DettaglioTimbratura, completo: Boolean) {
        val letto = dettagli[d.id]
        when {
            completo -> dettagli[d.id] = d
            letto != null -> dettagli[d.id] = d.copy(attestazione = letto.attestazione, wifi = letto.wifi, notices = letto.notices)
        }
        val marcata = d.stamps.any { it.target == "video" && it.qualified && it.status == "complete" }
        aggiornaVoce(d.id) {
            it.copy(
                contestata = d.contested, daTenere = d.keep, marcata = it.marcata || marcata,
                video = when {
                    d.videoCancellatoIl != null -> "cancellato"
                    d.videoRicevuto -> "ricevuto"
                    else -> "mancante"
                },
            )
        }
    }

    // --- link di condivisione ---------------------------------------------------------------------------------------

    /** I miei link (`GET /admin/shares`): null finché non si chiedono (impronta). */
    var link by mutableStateOf<List<LinkCondivisione>?>(null)
        private set

    /** Sopravvive alla rotazione; si azzera aprendo di nuovo Condividi. Non va nel Bundle salvato: contiene il token. */
    var nuovoLink by mutableStateOf<NuovoLink?>(null)

    suspend fun caricaLink(f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) return OK
        return tenta { link = api.links(f) }
    }

    suspend fun creaLink(ids: List<Long>, etichetta: String, giorni: Int, download: Boolean, f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) {
            nuovoLink = NuovoLink("https://timbro.example.it/s/dimostrazione", LocalDate.now().plusDays(giorni.toLong()))
            return OK
        }
        return tenta {
            val l = api.creaLink(RichiestaLink(ids, etichetta.trim(), giorni, download), f)
            nuovoLink = NuovoLink(l.url.orEmpty(), dataLocale(l.scade))
            link = link?.let { listOf(l.copy(url = null)) + it }
        }
    }

    suspend fun revocaLink(id: Long, f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) {
            link = link?.filterNot { it.id == id }
            return OK
        }
        return tenta {
            val l = api.revocaLink(id, f)
            link = link?.map { if (it.id == id) l else it }
        }
    }

    suspend fun accessi(id: Long, f: FirmatarioAdmin): Risultato<List<AccessoLink>> {
        if (dimostrazione) {
            val ieri = OffsetDateTime.now().minusDays(1).withNano(0)
            return Risultato.Ok(
                listOf(
                    AccessoLink(1, ieri.minusHours(2).toString(), "151.38.12.4", "Mozilla/5.0 (Windows NT 10.0) Firefox/146.0", "page"),
                    AccessoLink(2, ieri.toString(), "151.38.12.4", "Mozilla/5.0 (Windows NT 10.0) Firefox/146.0", "evidence:101"),
                ),
            )
        }
        return tenta { api.accessi(id, f) }
    }

    // --- contestazioni ------------------------------------------------------------------------------------------------

    /** Mandate in questa sessione, dalla più recente (quelle precedenti si leggono nel dettaglio, con l'impronta). */
    val contestazioni = mutableStateListOf<ContestazioneInviata>()

    /** Come [nuovoLink]: si azzera aprendo di nuovo Contesta. */
    var esitoContestazione by mutableStateOf<EsitoContestazioneProva?>(null)

    fun contestazione(id: Long): ContestazioneInviata? = contestazioni.firstOrNull { it.id == id }

    /**
     * Tutte le mail di contestazione dell'utente (`GET /admin/notices`, con l'impronta): si leggono quando servono e
     * restano per la sessione. Comprendono le segnalazioni di una giornata intera, che nel dettaglio di una timbratura
     * non ci sono.
     */
    val avvisi = mutableStateListOf<AvvisoMail>()
    var avvisiLetti by mutableStateOf(false)
        private set

    suspend fun caricaAvvisi(f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) {
            avvisiLetti = true
            return OK
        }
        return tenta {
            val letti = api.avvisi(f)
            avvisi.clear()
            avvisi.addAll(letti)
            avvisiLetti = true
        }
    }

    /**
     * Mail di contestazione della giornata di [t]: quelle della timbratura e quelle della sua giornata intera (sessione,
     * dettaglio e `/admin/notices`). Una mail su un'altra timbratura dello stesso giorno vale per tutta la giornata
     * (il link la contiene), quindi c'è anche quella.
     */
    fun contestazioniDi(t: Timbratura): List<ContestazioneInviata> = contestazioniDelGiorno(t.quando.toLocalDate(), t)

    /** Mail di contestazione di un giorno: [t] serve solo a riconoscere quelle sulla stessa timbratura. */
    fun contestazioniDelGiorno(giorno: LocalDate, t: Timbratura? = null): List<ContestazioneInviata> {
        val sessione = contestazioni.filter { it.giorno == giorno }
        val dalServer = buildList {
            t?.serverId?.let { dettagli[it] }?.notices?.let(::addAll)
            addAll(avvisi.filter { a -> a.day?.let(LocalDate::parse) == giorno })
        }.distinctBy { it.id }
        val lette = dalServer.map { it.perInterfaccia(giorno, sessione.firstOrNull { s -> s.id == it.id }) }
        return (lette + sessione.filter { s -> lette.none { it.id == s.id } }).sortedByDescending { it.creata }
    }

    /** La giornata ha già una mail (o un link) di contestazione: niente seconda contestazione. */
    fun giornataContestata(giorno: LocalDate): Boolean = contestazioniDelGiorno(giorno).isNotEmpty()

    private fun AvvisoMail.perInterfaccia(giorno: LocalDate, mandata: ContestazioneInviata?) = ContestazioneInviata(
        id, punchId?.let { pid -> timbrature.firstOrNull { it.serverId == pid }?.id }, day?.let(LocalDate::parse) ?: giorno,
        oraLocale(creatoIl), recipients, mandata?.scade, stato, inviatoIl?.let(::oraLocale), mandata?.nota, error,
    )

    /**
     * Contesta una timbratura ([t]) o segnala tutta la giornata [giorno] ([t] null): `POST /admin/punches/{id}/contest`
     * o `POST /admin/days/{data}/contest`, con l'impronta. Destinatari vuoti: solo il link.
     */
    suspend fun contesta(
        t: Timbratura?, giorno: LocalDate, destinatari: List<String>, giorni: Int, nota: String?, f: FirmatarioAdmin,
    ): Risultato<Unit> {
        val testo = nota?.trim()?.ifEmpty { null }
        if (dimostrazione) {
            t?.let { aggiornaFinta(it.id) { x -> x.copy(contestata = true) } }
            val c = ContestazioneInviata(
                -(contestazioni.size + 1L), t?.id, giorno, LocalDateTime.now().withNano(0), destinatari,
                LocalDate.now().plusDays(giorni.toLong()), if (destinatari.isEmpty()) StatoAvviso.INVIATA else StatoAvviso.IN_INVIO,
                nota = testo,
            )
            contestazioni.add(0, c)
            esitoContestazione = EsitoContestazioneProva(c.id, "https://timbro.example.it/s/dimostrazione")
            return OK
        }
        if (t != null && t.serverId == null) return nonSulServer()
        val r = RichiestaContestazione(giorni, destinatari, testo)
        val esito = tenta {
            val e = if (t != null) api.contesta(t.serverId!!, r, f) else api.segnalaGiorno(giorno, r, f)
            e.punch?.let { p -> aggiornaVoce(p.id) { it.copy(contestata = p.contested, daTenere = p.keep) } }
            val n = e.notice
            val c = ContestazioneInviata(
                n?.id ?: -e.share.id, t?.id, e.giorno, LocalDateTime.now().withNano(0), n?.recipients.orEmpty(),
                dataLocale(e.share.scade), n?.stato ?: StatoAvviso.INVIATA, n?.inviatoIl?.let(::oraLocale), testo, n?.error,
            )
            contestazioni.add(0, c)
            esitoContestazione = EsitoContestazioneProva(c.id, e.share.url.orEmpty())
        }
        // il server non ha l'SMTP: da qui l'app lo sa e propone di togliere i destinatari
        if (esito is Risultato.Errore && esito.codice == 409 && esito.messaggio.contains("non configurato")) aggiornaMail(false)
        return esito
    }

    // --- profilo e impostazioni ---------------------------------------------------------------------------------------

    private var profiloLetto by mutableStateOf<Profilo?>(null)

    /** L'SMTP del server: si sa da `GET/PUT /admin/profile` (impronta) o da un rifiuto; null = non ancora noto. */
    var mailConfigurata by mutableStateOf<Boolean?>(null)
        private set

    /**
     * Profilo per le contestazioni: da `/me` (senza impronta) o dall'ultimo `GET/PUT /admin/profile`. Finché non si sa
     * se il server manda le mail, si lascia provare.
     */
    val profilo: Profilo
        get() = profiloLetto ?: io?.profile.let { p ->
            Profilo(p?.nome, p?.matricola, p?.destinatari.orEmpty(), mailConfigurata ?: true, p?.email)
        }

    private fun aggiornaMail(configurata: Boolean) {
        mailConfigurata = configurata
        prefs.edit { putBoolean(MAIL, configurata) }
    }

    private fun usaProfilo(p: Profilo) {
        profiloLetto = p
        aggiornaMail(p.mailConfigurata)
        ioServer = ioServer?.copy(profile = ioServer!!.profile.copy(nome = p.nome, matricola = p.matricola, destinatari = p.destinatari, email = p.email))
            ?.also { salva(IO, Io.serializer(), it) }
        ioId?.let { mio -> utente(mio)?.let { aggiornaUtente(it.copy(profile = it.profile.copy(nome = p.nome, matricola = p.matricola, destinatari = p.destinatari, email = p.email))) } }
    }

    suspend fun caricaProfilo(f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) return OK
        return tenta { usaProfilo(api.profilo(f)) }
    }

    suspend fun salvaProfilo(p: Profilo, f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) {
            profiloLetto = p
            return OK
        }
        return tenta { usaProfilo(api.salvaProfilo(ModificaProfilo(p.nome.orEmpty(), p.matricola.orEmpty(), p.destinatari, p.email), f)) }
    }

    /** Conservazione dei video (`GET /admin/settings`): null finché non si chiede (impronta). */
    var conservazioneGiorni by mutableStateOf<Int?>(null)
        private set

    suspend fun caricaImpostazioni(f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) return OK
        return tenta { conservazioneGiorni = api.impostazioni(f).conservazioneGiorni }
    }

    /** Solo l'amministratore: `PATCH /admin/system/settings`. */
    suspend fun cambiaConservazione(giorni: Int, f: FirmatarioAdmin): Risultato<Unit> {
        if (dimostrazione) {
            conservazioneGiorni = giorni
            return OK
        }
        return tenta { conservazioneGiorni = api.cambiaConservazione(giorni, f).giorni }
    }

    // --- amministrazione di sistema (ruolo admin) ---------------------------------------------------------------------

    /** `GET /admin/system/users`, letti all'apertura di Utenti (impronta). */
    val utenti = mutableStateListOf<UtenteSistema>()
    var utentiCaricati by mutableStateOf(false)
        private set

    /** `GET /admin/system/schedules`, lette all'apertura di Articolazioni o delle assegnazioni (impronta). */
    val articolazioni = mutableStateListOf<Articolazione>()
    var articolazioniCaricate by mutableStateOf(false)
        private set

    fun utente(id: Long): UtenteSistema? = utenti.firstOrNull { it.id == id }

    fun articolazione(id: Long): Articolazione? = articolazioni.firstOrNull { it.id == id }

    /** Profili a cui un'articolazione è assegnata (non si può eliminare finché ce n'è uno). */
    fun assegnataA(id: Long): List<UtenteSistema> = utenti.filter { u -> u.assignments.any { it.articolazioneId == id } }

    suspend fun caricaUtenti(f: FirmatarioAdmin): Risultato<Unit> = sistema {
        val u = api.utenti(f)
        utenti.clear()
        utenti.addAll(u)
        utentiCaricati = true
    }

    suspend fun caricaArticolazioni(f: FirmatarioAdmin): Risultato<Unit> = sistema {
        val a = api.articolazioni(f)
        articolazioni.clear()
        articolazioni.addAll(a)
        articolazioniCaricate = true
    }

    private fun aggiornaUtente(u: UtenteSistema) {
        val i = utenti.indexOfFirst { it.id == u.id }
        if (i >= 0) utenti[i] = u else utenti += u
    }

    /** `POST /admin/system/users`. Restituisce l'id. */
    suspend fun creaUtente(username: String, ruolo: String, nome: String?, matricola: String?, f: FirmatarioAdmin): Risultato<Long> = sistema {
        val u = api.creaUtente(NuovoUtente(username, ruolo, nome, matricola), f)
        aggiornaUtente(u)
        u.id
    }

    /** `PUT /admin/system/users/{id}/profile`: nome, matricola e indirizzo personale; i destinatari restano quelli dell'utente. */
    suspend fun modificaProfiloUtente(id: Long, nome: String?, matricola: String?, email: String?, f: FirmatarioAdmin): Risultato<Unit> {
        val u = utente(id) ?: return Risultato.Errore("Utente non trovato: aggiorna l'elenco")
        return sistema {
            aggiornaUtente(api.profiloUtente(id, ModificaProfilo(nome.orEmpty(), matricola.orEmpty(), u.profile.destinatari, email), f))
            if (id == ioId) rileggiIo()
        }
    }

    /** `PUT /admin/system/users/{id}/role` (422 se è l'ultimo amministratore). */
    suspend fun cambiaRuolo(id: Long, ruolo: String, f: FirmatarioAdmin): Risultato<Unit> = sistema {
        aggiornaUtente(api.cambiaRuolo(id, ruolo, f))
        if (id == ioId) rileggiIo()
    }

    /** `POST /admin/system/users/{id}/pairing-code`: codice monouso per il telefono nuovo. */
    suspend fun codiceAssociazione(id: Long, f: FirmatarioAdmin): Risultato<CodiceAssociazione> = sistema { api.codiceAssociazione(id, f) }

    /** `DELETE /admin/system/devices/{id}`: non il telefono che si sta usando. */
    suspend fun revocaTelefono(utenteId: Long, telefonoId: String, f: FirmatarioAdmin): Risultato<Unit> {
        if (telefonoId == questoTelefono) return Risultato.Errore("Non puoi revocare il telefono che stai usando")
        return sistema {
            api.revocaTelefono(telefonoId, f)
            utente(utenteId)?.let { u ->
                val ora = OffsetDateTime.now().withNano(0).toString()
                aggiornaUtente(u.copy(devices = u.devices.map { if (it.id == telefonoId) it.copy(revocatoIl = ora) else it }))
            }
        }
    }

    /** `PUT /admin/system/users/{id}/assignments`: sostituisce le assegnazioni. */
    suspend fun assegna(id: Long, assegnazioni: List<RichiestaAssegnazione>, f: FirmatarioAdmin): Risultato<Unit> = sistema {
        val a = api.assegna(id, assegnazioni, f)
        utente(id)?.let { aggiornaUtente(it.copy(assignments = a)) }
        if (id == ioId) rileggiIo()
    }

    /** `POST /admin/system/schedules`. Restituisce l'id. */
    suspend fun creaArticolazione(nome: String, versione: VersioneOrario, f: FirmatarioAdmin): Risultato<Long> = sistema {
        val a = api.creaArticolazione(NuovaArticolazione(nome.trim(), versione), f)
        articolazioni += a
        a.id
    }

    /**
     * Nome e versioni: `PUT .../name` se cambia il nome, `PUT .../versions` per ogni versione nuova o cambiata e
     * `DELETE .../versions/{data}` per quelle tolte (prima le aggiunte: il server non lascia un'articolazione senza
     * versioni). Un'impronta per chiamata; dopo ogni chiamata riuscita lo stato è quello del server, così un errore a
     * metà lascia da salvare solo il resto.
     */
    suspend fun salvaArticolazione(a: Articolazione, f: FirmatarioAdmin): Risultato<Unit> {
        if (articolazione(a.id) == null) return Risultato.Errore("Articolazione non trovata: aggiorna l'elenco")
        return sistema {
            fun attuale() = articolazione(a.id)!!
            val nome = a.name.trim()
            if (nome != attuale().name) sostituisci(api.rinominaArticolazione(a.id, nome, f))
            val dopo = a.versions.associateBy { it.validoDal }
            a.versions.sortedBy { it.validoDal }.filter { v -> attuale().versions.firstOrNull { it.validoDal == v.validoDal } != v }
                .forEach { sostituisci(api.versione(a.id, it, f)) }
            attuale().versions.map { it.validoDal }.filter { it !in dopo }.forEach { sostituisci(api.eliminaVersione(a.id, it, f)) }
            if (assegnazioni().any { it.articolazioneId == a.id }) rileggiIo()
        }
    }

    private fun sostituisci(a: Articolazione) {
        val i = articolazioni.indexOfFirst { it.id == a.id }
        if (i >= 0) articolazioni[i] = a
        utenti.toList().forEach { u ->
            if (u.assignments.any { it.articolazioneId == a.id }) {
                aggiornaUtente(u.copy(assignments = u.assignments.map { if (it.articolazioneId == a.id) it.copy(nomeArticolazione = a.name) else it }))
            }
        }
    }

    /** `DELETE /admin/system/schedules/{id}`: il server la rifiuta (409) se è assegnata a qualcuno. */
    suspend fun eliminaArticolazione(id: Long, f: FirmatarioAdmin): Risultato<Unit> = sistema {
        api.eliminaArticolazione(id, f)
        articolazioni.removeAll { it.id == id }
    }

    // --- avvio (dopo tutte le proprietà: in Kotlin si inizializzano in ordine) -------------------------------------------

    init {
        // video e pacchetti scaricati in una sessione precedente: non restano sul telefono
        listOf("video", "prove").forEach { File(app.cacheDir, it).deleteRecursively() }
        ioServer = leggi(IO, Io.serializer())
        orarioServer = leggi(ORARIO, Orario.serializer())
        mailConfigurata = if (prefs.contains(MAIL)) prefs.getBoolean(MAIL, false) else null
        if (dimostrazione) caricaDimostrazione()
        viewModelScope.launch {
            cache = dao.tutte()
            ricomponi()
        }
    }

    // --- meccanica ------------------------------------------------------------------------------------------------------

    /** Le operazioni dell'amministratore non hanno una versione dimostrativa: cambierebbero dati veri. */
    private suspend fun <T> sistema(blocco: suspend () -> T): Risultato<T> = if (dimostrazione) NON_IN_PROVA else tenta(blocco)

    private fun nonSulServer() = Risultato.Errore("Ancora in invio: si gestisce quando è arrivata al server")

    private fun <T> leggi(chiave: String, s: KSerializer<T>): T? =
        prefs.getString(chiave, null)?.let { runCatching { ApiJson.decodeFromString(s, it) }.getOrNull() }

    private fun <T> salva(chiave: String, s: KSerializer<T>, valore: T) {
        prefs.edit { putString(chiave, ApiJson.encodeToString(s, valore)) }
    }

    private fun dettaglioFinto(t: Timbratura): DettaglioTimbratura {
        val ricevuta = t.quando.plusSeconds(2).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        val ora = t.quando.plusSeconds(3).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        return DettaglioTimbratura(
            t.id.toLong(), "dimostrazione-${t.id}", t.kind, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", ricevuta,
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752", !t.videoEliminato,
            stamps = if (t.stato == StatoInvio.MARCATA) {
                listOf(Marca("video", "accv", true, "complete", ora), Marca("video", "sectigo", true, "complete", ora), Marca("video", "freetsa", false, "complete", ora))
            } else {
                emptyList()
            },
            location = PosizioneRegistrata(41.9028, 12.4964, t.precisioneGpsM.toDouble(), "fused", false),
            attestation = EsitoAttestazione(t.attestazioneOk == true),
            keep = t.daTenere, contested = t.contestata, giustificativo = t.giustificativo.ifEmpty { null }, notes = t.note.ifEmpty { null },
            videoDisponibile = !t.videoEliminato, videoCancellatoIl = if (t.videoEliminato) ricevuta else null,
            attestazione = DettaglioAttestazione(
                t.attestazioneOk == true, livello = "tee", bloccato = true, avvio = "verified", patch = 202610,
                packages = listOf(buildJsonArray { add("it.zawardo.timbro"); add(29) }),
            ),
            wifi = RiepilogoWifi(
                t.retiWifi, t.retiGiaViste, 0,
                (0 until t.retiWifi).map { ReteVistaServer("a4:2b:b0:11:22:0$it", -48 - 4 * it, if (it % 2 == 0) 2437 else 5180, if (it < t.retiGiaViste) 12 else 0) },
            ),
        )
    }

    companion object {
        /** Giorni di storico per ogni lettura (il server ne dà al massimo 62). */
        const val GIORNI = 31

        private const val DIMOSTRAZIONE = "dimostrazione"
        private const val DAL = "storico_dal"
        private const val PRIMA = "storico_prima"
        private const val FINO = "storico_fino"
        private const val IO = "io"
        private const val ORARIO = "orario"
        private const val MAIL = "mail_configurata"

        private val OK = Risultato.Ok(Unit)
        private val NON_IN_PROVA = Risultato.Errore("Non disponibile nella modalità dimostrativa")
        private val ROMA: ZoneId = ZoneId.of("Europe/Rome")

        /** Il server ragiona per giorni in ora italiana. */
        private fun oggiRoma(): LocalDate = LocalDate.now(ROMA)

        private fun dataLocale(iso: String): LocalDate = oraLocale(iso).toLocalDate()

        private fun oraLocale(iso: String): LocalDateTime =
            OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().withNano(0)
    }
}

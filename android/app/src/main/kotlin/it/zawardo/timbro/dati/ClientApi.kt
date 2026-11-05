package it.zawardo.timbro.dati

import it.zawardo.timbro.BuildConfig
import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.core.api.AccessoLink
import it.zawardo.timbro.core.api.ApiJson
import it.zawardo.timbro.core.api.AvvisoMail
import it.zawardo.timbro.core.api.Articolazione
import it.zawardo.timbro.core.api.CambioRuolo
import it.zawardo.timbro.core.api.CodiceAssociazione
import it.zawardo.timbro.core.api.Conservazione
import it.zawardo.timbro.core.api.DettaglioTimbratura
import it.zawardo.timbro.core.api.ErroreApi
import it.zawardo.timbro.core.api.EsitoAssociazione
import it.zawardo.timbro.core.api.EsitoContestazione
import it.zawardo.timbro.core.api.FirmaAdmin
import it.zawardo.timbro.core.api.Impostazioni
import it.zawardo.timbro.core.api.Io
import it.zawardo.timbro.core.api.LinkCondivisione
import it.zawardo.timbro.core.api.ModificaProfilo
import it.zawardo.timbro.core.api.ModificaTimbratura
import it.zawardo.timbro.core.api.NuovaArticolazione
import it.zawardo.timbro.core.api.NuovoNome
import it.zawardo.timbro.core.api.NuovoUtente
import it.zawardo.timbro.core.api.Profilo
import it.zawardo.timbro.core.api.RichiestaAssegnazione
import it.zawardo.timbro.core.api.RichiestaAssociazione
import it.zawardo.timbro.core.api.RichiestaContestazione
import it.zawardo.timbro.core.api.RichiestaLink
import it.zawardo.timbro.core.api.RichiestaTimbratura
import it.zawardo.timbro.core.api.StatoTimbratura
import it.zawardo.timbro.core.api.Storico
import it.zawardo.timbro.core.api.TicketVideo
import it.zawardo.timbro.core.api.UtenteSistema
import it.zawardo.timbro.sicurezza.Ambiente
import it.zawardo.timbro.sicurezza.Associazione
import it.zawardo.timbro.sicurezza.Chiavi
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Risposta non 2xx del server, con il messaggio (`detail`) quando c'è. */
class ErroreHttp(val codice: Int, val dettaglio: String?) : Exception("HTTP $codice${dettaglio?.let { ": $it" } ?: ""}")

/** Firma con la chiave admin dopo l'impronta (BiometricPrompt): la fornisce l'interfaccia, che ha l'activity. */
fun interface FirmatarioAdmin {
    suspend fun firma(dati: ByteArray): ByteArray
}

/**
 * Corpi delle richieste: con i valori predefiniti (una versione dell'orario va mandata intera, come la riceve il
 * server) ma senza i null, che per il server vogliono dire "non mandato" (valore predefinito o campo da non cambiare).
 */
private val RichiesteJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Client dell'API del backend (docs/PROGETTO.md §6.3, §21, §22). Timeout su ogni chiamata. In release presenta a
 * nginx il certificato client: chiave nel Keystore e catena firmata dal server all'associazione ([MtlsKeyManager]).
 * L'associazione va al nome pubblico (enrollBase), l'unico senza certificato client.
 *
 * Lettura dello storico e dello stato delle proprie timbrature: solo token. Gestione e dati sensibili (posizione,
 * reti, note): token più firma admin, che chiede l'impronta a ogni chiamata ([FirmatarioAdmin]).
 */
class ClientApi(private val associazione: Associazione, private val chiavi: Chiavi, private val rete: Rete? = null) {
    private val json = "application/json".toMediaType()

    private fun base(mtls: Boolean): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .apply {
            val catena = chiavi.catenaTls()
            if (mtls && catena != null) {
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }.trustManagers.first { it is X509TrustManager } as X509TrustManager
                val ctx = SSLContext.getInstance("TLS").apply {
                    init(arrayOf<KeyManager>(MtlsKeyManager(chiavi.privataTls(), catena)), arrayOf(trust), null)
                }
                sslSocketFactory(ctx.socketFactory, trust)
            }
        }

    private val ambiente: Ambiente get() = associazione.ambiente

    // --- associazione -------------------------------------------------------------------------------------------

    /**
     * `POST /api/v1/enroll`. In debug, se il server non ha la CA (503: il backend sul PC senza CLIENT_CA_*), ripiega
     * su `/devices/register`: sul PC non c'è nginx né mTLS.
     */
    suspend fun associa(richiesta: RichiestaAssociazione): EsitoAssociazione = withContext(Dispatchers.IO) {
        val client = base(mtls = false).build()
        try {
            esegui(client, post("${ambiente.enrollBase}/api/v1/enroll", RichiestaAssociazione.serializer(), richiesta), EsitoAssociazione.serializer())
        } catch (e: ErroreHttp) {
            if (e.codice != 503 || !BuildConfig.DEBUG) throw e
            esegui(client, post("${ambiente.apiBase}/api/v1/devices/register", RichiestaAssociazione.serializer(), richiesta.copy(csr = null)), EsitoAssociazione.serializer())
        }
    }

    // --- con il token ---------------------------------------------------------------------------------------------

    suspend fun io(): Io = conToken("GET", "/api/v1/me", Io.serializer())

    suspend fun orario(): Orario = conToken("GET", "/api/v1/schedule", Orario.serializer())

    /** `GET /api/v1/history`: giorni completi in ora italiana fino a [fino] (null = oggi), [giorni] indietro. */
    suspend fun storico(fino: LocalDate?, giorni: Int): Storico =
        conToken("GET", "/api/v1/history?${Storico.query(fino, giorni)}", Storico.serializer())

    // --- timbrature (coda del telefono, InvioWorker) ---------------------------------------------------------------

    /** `POST /api/v1/punches`: byte esatti del manifest, firma e attestazione. Idempotente sul client_id. */
    suspend fun inviaTimbratura(manifest: ByteArray, firma: ByteArray, attestazione: List<String>?): StatoTimbratura =
        withContext(Dispatchers.IO) {
            val b64 = Base64.getEncoder()
            val corpo = RichiestaTimbratura(b64.encodeToString(manifest), b64.encodeToString(firma), attestazione)
            val req = richiesta("${ambiente.apiBase}/api/v1/punches".toHttpUrl())
                .post(ApiJson.encodeToString(RichiestaTimbratura.serializer(), corpo).toRequestBody(json)).build()
            esegui(base(ambiente.mtls).build(), req, StatoTimbratura.serializer())
        }

    /**
     * `PUT /api/v1/punches/{id}/video`: il file originale in streaming (fino a 50 MB). Il server lo accetta solo se
     * SHA-256 e dimensione coincidono con il manifest; idempotente. Timeout lunghi: su una rete lenta 50 MB ci mettono.
     */
    suspend fun inviaVideo(serverId: Long, file: File): StatoTimbratura = withContext(Dispatchers.IO) {
        val req = richiesta("${ambiente.apiBase}/api/v1/punches/$serverId/video".toHttpUrl())
            .put(file.asRequestBody("video/mp4".toMediaType())).build()
        val client = base(ambiente.mtls).writeTimeout(5, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES).build()
        esegui(client, req, StatoTimbratura.serializer())
    }

    /** `GET /api/v1/punches/{id}`: marche, video e attestazione di una timbratura di questo telefono. */
    suspend fun statoTimbratura(serverId: Long): StatoTimbratura =
        conToken("GET", "/api/v1/punches/$serverId", StatoTimbratura.serializer())

    // --- gestione delle proprie timbrature: token + firma admin (impronta) --------------------------------------

    suspend fun profilo(f: FirmatarioAdmin): Profilo = admin<Unit, Profilo>("GET", "/api/v1/admin/profile", null, null, f, Profilo.serializer())

    suspend fun salvaProfilo(p: ModificaProfilo, f: FirmatarioAdmin): Profilo =
        admin("PUT", "/api/v1/admin/profile", null, ModificaProfilo.serializer() to p, f, Profilo.serializer())

    /** Dettaglio con posizione, reti, note, attestazione completa e contestazioni inviate. */
    suspend fun dettaglio(id: Long, f: FirmatarioAdmin): DettaglioTimbratura =
        admin<Unit, DettaglioTimbratura>("GET", "/api/v1/admin/punches/$id", null, null, f, DettaglioTimbratura.serializer())

    suspend fun modifica(id: Long, m: ModificaTimbratura, f: FirmatarioAdmin): DettaglioTimbratura =
        admin("PATCH", "/api/v1/admin/punches/$id", null, ModificaTimbratura.serializer() to m, f, DettaglioTimbratura.serializer())

    suspend fun eliminaVideo(id: Long, f: FirmatarioAdmin): DettaglioTimbratura =
        admin<Unit, DettaglioTimbratura>("DELETE", "/api/v1/admin/punches/$id/video", null, null, f, DettaglioTimbratura.serializer())

    suspend fun ticketVideo(id: Long, f: FirmatarioAdmin): TicketVideo =
        admin<Unit, TicketVideo>("POST", "/api/v1/admin/punches/$id/video-ticket", null, null, f, TicketVideo.serializer())

    /** Il video del ticket (URL relativo, valido pochi minuti) in un file: il player lo legge da lì, anche con mTLS. */
    suspend fun scaricaVideo(ticket: TicketVideo, destinazione: File): File = withContext(Dispatchers.IO) {
        val req = richiesta("${ambiente.apiBase}${ticket.url}".toHttpUrl()).get().build()
        val client = base(ambiente.mtls).readTimeout(2, TimeUnit.MINUTES).callTimeout(10, TimeUnit.MINUTES).build()
        scaricaIn(client, req, destinazione)
    }

    /** Il pacchetto di prova (zip, anche decine di MB) in un file, da condividere. */
    suspend fun scaricaPacchetto(id: Long, destinazione: File, f: FirmatarioAdmin): File {
        val req = richiestaAdmin("GET", "/api/v1/admin/punches/$id/evidence.zip", null, ByteArray(0), f)
        return withContext(Dispatchers.IO) {
            val client = base(ambiente.mtls).readTimeout(5, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES).build()
            scaricaIn(client, req, destinazione)
        }
    }

    suspend fun contesta(id: Long, r: RichiestaContestazione, f: FirmatarioAdmin): EsitoContestazione =
        admin("POST", "/api/v1/admin/punches/$id/contest", null, RichiestaContestazione.serializer() to r, f, EsitoContestazione.serializer())

    suspend fun segnalaGiorno(giorno: LocalDate, r: RichiestaContestazione, f: FirmatarioAdmin): EsitoContestazione =
        admin("POST", "/api/v1/admin/days/$giorno/contest", null, RichiestaContestazione.serializer() to r, f, EsitoContestazione.serializer())

    /** Tutte le mail di contestazione dell'utente, anche quelle di una giornata intera (`punch_id` nullo). */
    suspend fun avvisi(f: FirmatarioAdmin): List<AvvisoMail> =
        admin<Unit, List<AvvisoMail>>("GET", "/api/v1/admin/notices", "limit=500", null, f, ListSerializer(AvvisoMail.serializer()))

    // --- link di condivisione --------------------------------------------------------------------------------------

    suspend fun links(f: FirmatarioAdmin): List<LinkCondivisione> =
        admin<Unit, List<LinkCondivisione>>("GET", "/api/v1/admin/shares", null, null, f, ListSerializer(LinkCondivisione.serializer()))

    suspend fun creaLink(r: RichiestaLink, f: FirmatarioAdmin): LinkCondivisione =
        admin("POST", "/api/v1/admin/shares", null, RichiestaLink.serializer() to r, f, LinkCondivisione.serializer())

    suspend fun revocaLink(id: Long, f: FirmatarioAdmin): LinkCondivisione =
        admin<Unit, LinkCondivisione>("DELETE", "/api/v1/admin/shares/$id", null, null, f, LinkCondivisione.serializer())

    suspend fun accessi(id: Long, f: FirmatarioAdmin): List<AccessoLink> =
        admin<Unit, List<AccessoLink>>("GET", "/api/v1/admin/shares/$id/accesses", null, null, f, ListSerializer(AccessoLink.serializer()))

    suspend fun impostazioni(f: FirmatarioAdmin): Impostazioni =
        admin<Unit, Impostazioni>("GET", "/api/v1/admin/settings", null, null, f, Impostazioni.serializer())

    // --- amministrazione di sistema (ruolo admin) ------------------------------------------------------------------

    suspend fun utenti(f: FirmatarioAdmin): List<UtenteSistema> =
        admin<Unit, List<UtenteSistema>>("GET", "/api/v1/admin/system/users", null, null, f, ListSerializer(UtenteSistema.serializer()))

    suspend fun creaUtente(u: NuovoUtente, f: FirmatarioAdmin): UtenteSistema =
        admin("POST", "/api/v1/admin/system/users", null, NuovoUtente.serializer() to u, f, UtenteSistema.serializer())

    suspend fun cambiaRuolo(id: Long, ruolo: String, f: FirmatarioAdmin): UtenteSistema =
        admin("PUT", "/api/v1/admin/system/users/$id/role", null, CambioRuolo.serializer() to CambioRuolo(ruolo), f, UtenteSistema.serializer())

    suspend fun profiloUtente(id: Long, p: ModificaProfilo, f: FirmatarioAdmin): UtenteSistema =
        admin("PUT", "/api/v1/admin/system/users/$id/profile", null, ModificaProfilo.serializer() to p, f, UtenteSistema.serializer())

    suspend fun codiceAssociazione(id: Long, f: FirmatarioAdmin): CodiceAssociazione =
        admin<Unit, CodiceAssociazione>("POST", "/api/v1/admin/system/users/$id/pairing-code", null, null, f, CodiceAssociazione.serializer())

    suspend fun revocaTelefono(telefonoId: String, f: FirmatarioAdmin) {
        admin<Unit, kotlinx.serialization.json.JsonObject>(
            "DELETE", "/api/v1/admin/system/devices/$telefonoId", null, null, f, kotlinx.serialization.json.JsonObject.serializer(),
        )
    }

    suspend fun assegna(id: Long, a: List<RichiestaAssegnazione>, f: FirmatarioAdmin): List<it.zawardo.timbro.core.api.Assegnazione> =
        admin(
            "PUT", "/api/v1/admin/system/users/$id/assignments", null, ListSerializer(RichiestaAssegnazione.serializer()) to a, f,
            ListSerializer(it.zawardo.timbro.core.api.Assegnazione.serializer()),
        )

    suspend fun articolazioni(f: FirmatarioAdmin): List<Articolazione> =
        admin<Unit, List<Articolazione>>("GET", "/api/v1/admin/system/schedules", null, null, f, ListSerializer(Articolazione.serializer()))

    suspend fun creaArticolazione(a: NuovaArticolazione, f: FirmatarioAdmin): Articolazione =
        admin("POST", "/api/v1/admin/system/schedules", null, NuovaArticolazione.serializer() to a, f, Articolazione.serializer())

    suspend fun rinominaArticolazione(id: Long, nome: String, f: FirmatarioAdmin): Articolazione =
        admin("PUT", "/api/v1/admin/system/schedules/$id/name", null, NuovoNome.serializer() to NuovoNome(nome), f, Articolazione.serializer())

    /** Aggiunge una versione datata o sostituisce quella con la stessa data. */
    suspend fun versione(id: Long, v: VersioneOrario, f: FirmatarioAdmin): Articolazione =
        admin("PUT", "/api/v1/admin/system/schedules/$id/versions", null, VersioneOrario.serializer() to v, f, Articolazione.serializer())

    suspend fun eliminaVersione(id: Long, dal: LocalDate, f: FirmatarioAdmin): Articolazione =
        admin<Unit, Articolazione>("DELETE", "/api/v1/admin/system/schedules/$id/versions/$dal", null, null, f, Articolazione.serializer())

    suspend fun eliminaArticolazione(id: Long, f: FirmatarioAdmin) {
        admin<Unit, kotlinx.serialization.json.JsonObject>(
            "DELETE", "/api/v1/admin/system/schedules/$id", null, null, f, kotlinx.serialization.json.JsonObject.serializer(),
        )
    }

    suspend fun cambiaConservazione(giorni: Int, f: FirmatarioAdmin): Conservazione =
        admin("PATCH", "/api/v1/admin/system/settings", null, Conservazione.serializer() to Conservazione(giorni), f, Conservazione.serializer())

    // --- meccanica ---------------------------------------------------------------------------------------------------

    /**
     * Richiesta admin firmata: la stringa canonica usa percorso e query **codificati** dell'URL finale (FirmaAdmin), cioè
     * esattamente quello che OkHttp manda e che il server legge da raw_path e query_string.
     */
    suspend fun <B, T> admin(
        metodo: String,
        percorso: String,
        query: String?,
        corpo: Pair<SerializationStrategy<B>, B>?,
        firmatario: FirmatarioAdmin,
        risposta: DeserializationStrategy<T>,
    ): T {
        val byte = corpo?.let { (s, v) -> RichiesteJson.encodeToString(s, v).toByteArray() } ?: ByteArray(0)
        val req = richiestaAdmin(metodo, percorso, query, byte, firmatario)
        return withContext(Dispatchers.IO) { esegui(base(ambiente.mtls).build(), req, risposta) }
    }

    private suspend fun richiestaAdmin(metodo: String, percorso: String, query: String?, byte: ByteArray, firmatario: FirmatarioAdmin): Request {
        val url = "${ambiente.apiBase}$percorso".toHttpUrl().newBuilder().encodedQuery(query).build()
        val ora = FirmaAdmin.ora()
        val nonce = FirmaAdmin.nonce()
        val canonica = FirmaAdmin.canonica(metodo, url.encodedPath, url.encodedQuery, ora, nonce, byte)
        val firma = firmatario.firma(canonica)  // impronta: sul thread principale
        val builder = richiesta(url).header("X-Timbro-Time", ora).header("X-Timbro-Nonce", nonce)
            .header("X-Timbro-Signature", Base64.getEncoder().encodeToString(firma))
        return if (metodo == "GET") builder.get().build() else builder.method(metodo, byte.toRequestBody(json)).build()
    }

    private suspend fun <T> conToken(metodo: String, percorso: String, risposta: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val req = richiesta("${ambiente.apiBase}$percorso".toHttpUrl()).method(metodo, null).build()
            esegui(base(ambiente.mtls).build(), req, risposta)
        }

    private fun richiesta(url: HttpUrl): Request.Builder {
        val token = associazione.token() ?: throw IllegalStateException("telefono non associato")
        return Request.Builder().url(url).header("Authorization", "Bearer $token").header("User-Agent", "Timbro/${BuildConfig.VERSION_NAME}")
    }

    private fun <B> post(url: String, s: SerializationStrategy<B>, valore: B): Request =
        Request.Builder().url(url).header("User-Agent", "Timbro/${BuildConfig.VERSION_NAME}")
            .post(RichiesteJson.encodeToString(s, valore).toRequestBody(json)).build()

    /** Esegue la chiamata e aggiorna lo stato della rete: una risposta qualsiasi vuol dire server raggiungibile. */
    private fun chiama(client: OkHttpClient, req: Request): Response =
        try {
            client.newCall(req).execute().also { rete?.risposta() }
        } catch (e: IOException) {
            rete?.irraggiungibile()
            throw e
        }

    private fun <T> esegui(client: OkHttpClient, req: Request, risposta: DeserializationStrategy<T>): T =
        chiama(client, req).use { r ->
            val testo = r.body.string()
            if (!r.isSuccessful) throw errore(r.code, testo)
            ApiJson.decodeFromString(risposta, testo)
        }

    /** La risposta in un file (scritto accanto e poi rinominato: un download interrotto non lascia un file a metà). */
    private fun scaricaIn(client: OkHttpClient, req: Request, destinazione: File): File =
        chiama(client, req).use { r ->
            if (!r.isSuccessful) throw errore(r.code, r.body.string())
            destinazione.parentFile?.mkdirs()
            val parziale = File(destinazione.path + ".part")
            parziale.outputStream().use { out -> r.body.byteStream().use { it.copyTo(out) } }
            if (!parziale.renameTo(destinazione)) {
                parziale.delete()
                throw IOException("file non salvato")
            }
            destinazione
        }

    private fun errore(codice: Int, testo: String): ErroreHttp =
        ErroreHttp(codice, runCatching { ApiJson.decodeFromString(ErroreApi.serializer(), testo).detail }.getOrNull())
}

/**
 * Presenta a nginx il certificato del telefono: chiave nel Keystore (non esportabile: Conscrypt la usa per firmare
 * l'handshake senza leggerla) e catena telefono → intermedia di Timbro.
 */
private class MtlsKeyManager(private val chiave: PrivateKey, private val catena: Array<X509Certificate>) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?) = catena
    override fun getPrivateKey(alias: String?) = chiave

    companion object {
        const val ALIAS = "timbro"
    }
}

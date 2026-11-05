package it.zawardo.timbro.core.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Firma delle operazioni di gestione (`/api/v1/admin/...`), come la verifica il server in
 * `timbro/api/admin_auth.py`. Il telefono firma con la chiave admin (sbloccata dall'impronta) la stringa:
 *
 *     TIMBRO-ADMIN-1\n<METODO>\n<percorso>[?<query>]\n<ora>\n<nonce>\n<sha256 esadecimale del corpo>
 *
 * Percorso e query sono quelli **codificati che il client HTTP manda davvero** (con OkHttp: `HttpUrl.encodedPath`
 * e `HttpUrl.encodedQuery` dell'URL finale): il server firma-verifica i byte grezzi della richiesta, quindi una
 * codifica diversa (spazi, `+`, `:`) dà 401. L'ora deve stare entro la tolleranza del server (2 minuti) e il nonce
 * non si riusa mai. Esempi condivisi con i test del backend: `tests/fixtures/contratto/firma_admin.json`.
 */
object FirmaAdmin {
    const val PREFISSO = "TIMBRO-ADMIN-1"
    private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val ORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

    fun canonica(metodo: String, percorso: String, query: String?, ora: String, nonce: String, corpo: ByteArray): ByteArray {
        require(percorso.startsWith("/")) { "percorso non assoluto" }
        require('#' !in percorso && '#' !in query.orEmpty()) { "'#' non ammesso nella richiesta" }
        require(NONCE.matches(nonce)) { "nonce non valido" }
        val bersaglio = if (query.isNullOrEmpty()) percorso else "$percorso?$query"
        val impronta = MessageDigest.getInstance("SHA-256").digest(corpo).joinToString("") { "%02x".format(it) }
        return listOf(PREFISSO, metodo.uppercase(Locale.ROOT), bersaglio, ora, nonce, impronta)
            .joinToString("\n")
            .toByteArray(Charsets.UTF_8)
    }

    /** Ora ISO 8601 in UTC con i millesimi, es. `2026-11-05T12:00:00.000Z` (Python la legge con fromisoformat). */
    fun ora(istante: Instant = Instant.now()): String = ORA.format(istante.atOffset(ZoneOffset.UTC))

    /** 18 byte casuali in base64url senza padding: 24 caratteri. */
    fun nonce(caso: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(caso::nextBytes))

    /**
     * Intestazioni da aggiungere alla richiesta (oltre ad `Authorization: Bearer <token>`). [firma] riceve i byte
     * della stringa canonica e restituisce la firma ECDSA SHA-256 in DER: sul telefono `Signature("SHA256withECDSA")`
     * con la chiave admin dell'AndroidKeyStore, dopo BiometricPrompt.
     */
    fun intestazioni(
        metodo: String,
        percorso: String,
        query: String?,
        corpo: ByteArray,
        firma: (ByteArray) -> ByteArray,
        ora: String = ora(),
        nonce: String = nonce(),
    ): Map<String, String> = mapOf(
        "X-Timbro-Time" to ora,
        "X-Timbro-Nonce" to nonce,
        "X-Timbro-Signature" to Base64.getEncoder().encodeToString(firma(canonica(metodo, percorso, query, ora, nonce, corpo))),
    )
}

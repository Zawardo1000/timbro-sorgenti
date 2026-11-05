package it.zawardo.timbro.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/enroll` (docs/PROGETTO.md §22): codice di associazione, chiavi pubbliche del telefono e richiesta di
 * certificato per la chiave TLS. Senza [csr] è la stessa richiesta di `POST /api/v1/devices/register` (ripiego in
 * debug quando il server non ha la CA: niente mTLS sul PC). I campi null non vanno nel JSON ([ApiJson]).
 */
@Serializable
data class RichiestaAssociazione(
    @SerialName("pairing_code") val codice: String,
    val name: String,
    @SerialName("public_key_pem") val chiaveTimbrature: String,
    @SerialName("admin_public_key_pem") val chiaveAdmin: String,
    @SerialName("attestation_chain_pem") val attestazione: String? = null,
    @SerialName("csr_pem") val csr: String? = null,
) {
    init {
        require(codice.isNotBlank() && codice.length <= 64) { "codice di associazione non valido" }
        require(name.isNotBlank() && name.length <= 100) { "nome del telefono da 1 a 100 caratteri" }
    }

    companion object {
        /** Il codice come lo scrive una persona: spazi ai lati tolti (dentro non ce ne sono: token_urlsafe). */
        fun pulisciCodice(testo: String): String = testo.trim().replace(" ", "")
    }
}

/** Risposta di `/enroll` (con certificato e catena) o di `/devices/register` (senza). */
@Serializable
data class EsitoAssociazione(
    @SerialName("device_id") val telefonoId: String,
    @SerialName("api_token") val token: String,
    @SerialName("client_cert_pem") val certificato: String? = null,
    /** Certificato del telefono e intermedia di Timbro, in quest'ordine: è la catena da presentare a nginx. */
    @SerialName("client_chain_pem") val catena: String? = null,
)

/** Errore dell'API con il messaggio del server (campo `detail` di FastAPI). */
@Serializable
data class ErroreApi(val detail: String? = null)

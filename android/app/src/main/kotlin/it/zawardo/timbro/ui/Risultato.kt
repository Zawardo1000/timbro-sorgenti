package it.zawardo.timbro.ui

import it.zawardo.timbro.dati.ErroreHttp
import it.zawardo.timbro.sicurezza.AutenticazioneAnnullata
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Esito di un'operazione con il server, con un messaggio chiaro in caso di errore (mai un crash). */
sealed interface Risultato<out T> {
    data class Ok<T>(val valore: T) : Risultato<T>
    data class Errore(val messaggio: String, val codice: Int? = null) : Risultato<Nothing>
}

val Risultato<*>.errore: String? get() = (this as? Risultato.Errore)?.messaggio

suspend fun <T> tenta(blocco: suspend () -> T): Risultato<T> = try {
    Risultato.Ok(blocco())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Risultato.Errore(spiega(e), (e as? ErroreHttp)?.codice)
}

/** Il messaggio da mostrare: per 403, 404, 409 e 422 conta il dettaglio del server (es. "è l'ultimo amministratore"). */
fun spiega(e: Exception): String = when (e) {
    is ErroreHttp -> when (e.codice) {
        401 -> "Il server non riconosce questo telefono: è stato revocato?"
        403 -> e.dettaglio?.let { "Non permesso: $it" } ?: "Operazione non permessa"
        404 -> e.dettaglio?.let { "Non trovato: $it" } ?: "Non trovato sul server"
        409 -> e.dettaglio ?: "Il server ha rifiutato l'operazione"
        422 -> "Dati rifiutati dal server: ${e.dettaglio ?: "richiesta non valida"}"
        in 500..599 -> "Il server ha un problema (${e.codice}): riprova fra poco"
        else -> "Il server ha risposto ${e.codice}${e.dettaglio?.let { ": $it" } ?: ""}"
    }
    is AutenticazioneAnnullata -> "Impronta non confermata: ${e.message}"
    is IOException -> "Server non raggiungibile: controlla la rete e riprova"
    else -> e.message ?: e.javaClass.simpleName
}

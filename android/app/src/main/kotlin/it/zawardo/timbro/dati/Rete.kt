package it.zawardo.timbro.dati

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stato della rete per la pastiglia di Timbra: connettività del telefono (ConnectivityManager) e l'esito dell'ultima
 * chiamata al server (ClientApi lo aggiorna: una risposta HTTP qualsiasi vuol dire raggiungibile, un errore di rete no).
 */
class Rete(contesto: Context) {
    private val cm = contesto.getSystemService(ConnectivityManager::class.java)

    var connesso by mutableStateOf(connessoOra())
        private set

    /** null finché non si è provato; poi l'esito dell'ultima chiamata al server. */
    var serverRaggiungibile by mutableStateOf<Boolean?>(null)
        private set

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { connesso = true }

                // la rete predefinita è persa: se ce n'è un'altra, arriva subito onAvailable (activeNetwork qui può
                // ancora restituire quella appena persa)
                override fun onLost(network: Network) { connesso = false }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    connesso = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    private fun connessoOra(): Boolean =
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun risposta() {
        serverRaggiungibile = true
    }

    fun irraggiungibile() {
        serverRaggiungibile = false
    }
}

/** Cosa dice la pastiglia di Timbra. */
enum class StatoRete { ONLINE, OFFLINE, SERVER_GIU }

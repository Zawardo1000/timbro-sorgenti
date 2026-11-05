package it.zawardo.timbro.sicurezza

import android.content.Context
import android.util.Base64
import it.zawardo.timbro.BuildConfig

/** Indirizzi del backend: quelli di BuildConfig, cambiabili dalla schermata di debug (solo nella build debug). */
data class Ambiente(val apiBase: String, val enrollBase: String, val mtls: Boolean) {
    companion object {
        val PREDEFINITO = Ambiente(BuildConfig.API_BASE, BuildConfig.ENROLL_BASE, BuildConfig.MTLS)
    }
}

/**
 * Stato dell'associazione del telefono, nelle preferenze private dell'app (escluse da backup e trasferimento):
 * id del telefono, token dell'API cifrato con la chiave AES del Keystore, nome e server con cui è stato associato.
 * Le chiavi stanno nel Keystore ([Chiavi]); senza quelle l'associazione non vale.
 */
class Associazione(contesto: Context, private val chiavi: Chiavi) {
    private val prefs = contesto.getSharedPreferences("associazione", Context.MODE_PRIVATE)

    val associato: Boolean get() = telefonoId != null && prefs.contains("token") && chiavi.associato()

    val telefonoId: String? get() = prefs.getString("device_id", null)
    val nomeTelefono: String? get() = prefs.getString("nome", null)
    val conCertificato: Boolean get() = prefs.getBoolean("certificato", false)

    var ambiente: Ambiente
        get() = Ambiente(
            prefs.getString("api_base", null) ?: Ambiente.PREDEFINITO.apiBase,
            prefs.getString("enroll_base", null) ?: Ambiente.PREDEFINITO.enrollBase,
            prefs.getBoolean("mtls", Ambiente.PREDEFINITO.mtls),
        )
        set(v) {
            prefs.edit().putString("api_base", v.apiBase.trimEnd('/')).putString("enroll_base", v.enrollBase.trimEnd('/'))
                .putBoolean("mtls", v.mtls).apply()
        }

    fun token(): String? {
        val iv = prefs.getString("token_iv", null) ?: return null
        val dati = prefs.getString("token", null) ?: return null
        return runCatching { chiavi.decifra(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(dati, Base64.NO_WRAP)) }.getOrNull()
    }

    fun salva(telefonoId: String, token: String, nome: String, conCertificato: Boolean) {
        val (iv, dati) = chiavi.cifra(token)
        prefs.edit()
            .putString("device_id", telefonoId)
            .putString("token_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(dati, Base64.NO_WRAP))
            .putString("nome", nome)
            .putBoolean("certificato", conCertificato)
            .apply()
    }

    /**
     * Dimentica il telefono: tutte le chiavi del Keystore (anche quella del token), token, id e nome. Resta solo il
     * server scelto a mano (debug). Sul server il telefono va revocato dall'amministratore.
     */
    fun dissocia() {
        chiavi.cancellaTutto()
        val amb = ambiente
        prefs.edit().clear().commit()
        if (amb != Ambiente.PREDEFINITO) ambiente = amb
    }
}

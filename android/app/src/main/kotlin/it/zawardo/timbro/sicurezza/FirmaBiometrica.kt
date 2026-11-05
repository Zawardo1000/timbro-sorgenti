package it.zawardo.timbro.sicurezza

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import it.zawardo.timbro.dati.FirmatarioAdmin
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AutenticazioneAnnullata(messaggio: String) : Exception(messaggio)

/**
 * Firma con la chiave admin, sbloccata dall'impronta per quella sola firma (CryptoObject). Da Android 11 vale anche
 * il PIN del telefono; su Android 10 solo l'impronta forte, con il pulsante Annulla (docs/PROGETTO.md §20).
 */
class FirmaBiometrica(private val activity: FragmentActivity, private val chiavi: Chiavi) : FirmatarioAdmin {
    override suspend fun firma(dati: ByteArray): ByteArray = withContext(Dispatchers.Main) {
        val firma = chiavi.firmaAdminDaSbloccare()
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val s = result.cryptoObject?.signature
                        if (s == null) {
                            cont.resumeWithException(IllegalStateException("firma non sbloccata"))
                            return
                        }
                        runCatching { s.update(dati); s.sign() }
                            .onSuccess { cont.resume(it) }
                            .onFailure { cont.resumeWithException(it) }
                    }

                    override fun onAuthenticationError(codice: Int, messaggio: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(AutenticazioneAnnullata(messaggio.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Conferma l'operazione")
                .setSubtitle("Gestione di Timbro: serve la tua impronta")
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    } else {
                        setAllowedAuthenticators(BIOMETRIC_STRONG)
                        setNegativeButtonText("Annulla")
                    }
                }
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(firma))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

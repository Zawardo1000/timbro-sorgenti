package it.zawardo.timbro.sicurezza

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import it.zawardo.timbro.core.Csr
import it.zawardo.timbro.core.Pem
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Le chiavi del telefono, tutte nel chip sicuro (AndroidKeyStore): non si possono leggere né copiare, nemmeno
 * dall'app. StrongBox se il telefono lo ha, altrimenti il TEE.
 *
 * - **timbrature**: firma i manifest (EC P-256, SHA-256), senza autenticazione: si timbra in fretta;
 * - **admin**: firma le operazioni di gestione (FirmaAdmin), solo dopo l'impronta (o il PIN, da Android 11). Su
 *   Android 10 biometric 1.1.0 con un CryptoObject accetta solo BIOMETRIC_STRONG (docs/PROGETTO.md §20);
 * - **tls**: chiave del certificato client per mTLS; il certificato lo firma il server all'associazione (§22) e si
 *   installa qui con setKeyEntry. DIGEST_NONE: TLS firma digest già calcolati;
 * - **token**: AES-GCM che cifra il token dell'API nelle preferenze.
 */
class Chiavi {
    private val ks: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun associato(): Boolean = ks.containsAlias(TIMBRATURE) && ks.containsAlias(ADMIN) && ks.containsAlias(TLS)

    /** Crea le tre chiavi (sostituendo quelle vecchie). [sfida]: challenge dell'attestazione della chiave delle timbrature. */
    fun crea(sfida: ByteArray?) {
        cancella()
        genera(TIMBRATURE, sfida) {
            setDigests(KeyProperties.DIGEST_SHA256)
        }
        genera(ADMIN, null) {
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(true)
            // un'impronta aggiunta dopo non invalida la chiave: con il PIN (Android 11+) si sbloccherebbe comunque, e
            // perderla vorrebbe dire riassociare il telefono
            setInvalidatedByBiometricEnrollment(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(-1)  // ogni uso, con CryptoObject e BIOMETRIC_STRONG
            }
        }
        genera(TLS, null) {
            setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        }
    }

    /** Toglie le tre chiavi dell'associazione (prima di crearne di nuove, o se l'associazione non riesce). */
    fun cancella() {
        for (a in listOf(TIMBRATURE, ADMIN, TLS)) if (ks.containsAlias(a)) ks.deleteEntry(a)
    }

    /**
     * Dissociazione: tutte le chiavi dell'app nel Keystore, cioè le tre dell'associazione, quella AES del token e le
     * attestazioni usa e getta rimaste da un processo morto a metà.
     */
    fun cancellaTutto() {
        for (a in ks.aliases().toList()) if (a.startsWith("timbro.")) runCatching { ks.deleteEntry(a) }
    }

    fun pubblicaPem(alias: String): String = Pem.codifica("PUBLIC KEY", ks.getCertificate(alias).publicKey.encoded)

    /** Catena di attestazione della chiave (dalla foglia alla radice), in PEM; null se il telefono non la dà. */
    fun attestazionePem(alias: String): String? =
        ks.getCertificateChain(alias)?.takeIf { it.size > 1 }?.joinToString("") { Pem.codifica("CERTIFICATE", it.encoded) }

    /** CSR PKCS#10 della chiave TLS, firmato nel chip. */
    fun csrTls(nome: String): String {
        val chiave = ks.getKey(TLS, null) as PrivateKey
        val der = Csr.der(ks.getCertificate(TLS).publicKey.encoded, nome) { info ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(chiave); update(info) }.sign()
        }
        return Csr.pem(der)
    }

    /** Installa sulla chiave TLS il certificato firmato dal server (telefono + intermedia). */
    fun installaCatenaTls(catenaPem: String) {
        val cf = CertificateFactory.getInstance("X.509")
        val catena: Array<Certificate> = Pem.blocchi(catenaPem).map { cf.generateCertificate(it.inputStream()) }.toTypedArray()
        require(catena.isNotEmpty()) { "catena vuota" }
        require(catena[0].publicKey == ks.getCertificate(TLS).publicKey) { "il certificato non è per la chiave TLS di questo telefono" }
        ks.setKeyEntry(TLS, ks.getKey(TLS, null), null, catena)
    }

    /** Catena TLS installata (per il KeyManager di mTLS); null finché il server non l'ha firmata. */
    fun catenaTls(): Array<X509Certificate>? = ks.getCertificateChain(TLS)
        ?.map { it as X509Certificate }
        ?.takeIf { it.size > 1 || it.firstOrNull()?.let { c -> c.issuerX500Principal != c.subjectX500Principal } == true }
        ?.toTypedArray()

    fun privataTls(): PrivateKey = ks.getKey(TLS, null) as PrivateKey

    /** Firma con la chiave delle timbrature (manifest). */
    fun firmaTimbratura(dati: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(TIMBRATURE, null) as PrivateKey); update(dati) }.sign()

    /**
     * Attestazione hardware della timbratura (docs/PROGETTO.md §19): chiave EC usa e getta con challenge = [sfida]
     * (SHA-256 dei byte del manifest), catena di certificati dalla foglia alla radice in DER base64, poi la chiave si
     * cancella. Null se il telefono non sa attestare: la timbratura parte lo stesso, il server lo segnala. Su un
     * emulatore la catena c'è ma è "software", e il server lo scrive nell'esito.
     */
    fun attestaManifest(sfida: ByteArray): List<String>? {
        val alias = "timbro.attestazione.${java.util.UUID.randomUUID()}"
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        try {
            for (strongBox in listOf(true, false)) {
                try {
                    kpg.initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setAttestationChallenge(sfida)
                            .apply { if (strongBox) setIsStrongBoxBacked(true) }
                            .build(),
                    )
                    kpg.generateKeyPair()
                    break
                } catch (_: StrongBoxUnavailableException) {
                    continue
                } catch (e: java.security.ProviderException) {
                    if (!strongBox) return null  // il TEE non sa attestare
                }
            }
            val catena = ks.getCertificateChain(alias) ?: return null
            if (catena.size < 2) return null  // certificato autofirmato: nessuna attestazione
            val b64 = java.util.Base64.getEncoder()
            return catena.map { b64.encodeToString(it.encoded) }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { if (ks.containsAlias(alias)) ks.deleteEntry(alias) }
        }
    }

    /** Signature pronta per BiometricPrompt (CryptoObject): si completa dopo l'impronta. */
    fun firmaAdminDaSbloccare(): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ADMIN, null) as PrivateKey) }

    /** Dove sta davvero la chiave: "StrongBox", "TEE" o "software" (emulatore). */
    fun livello(alias: String): String = runCatching {
        val k = ks.getKey(alias, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(k.algorithm, KEYSTORE).getKeySpec(k, android.security.keystore.KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                else -> "software"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "hardware" else "software"
        }
    }.getOrDefault("?")

    // --- token cifrato ------------------------------------------------------------------------------------------

    fun cifra(testo: String): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chiaveToken()) }
        return c.iv to c.doFinal(testo.toByteArray(Charsets.UTF_8))
    }

    fun decifra(iv: ByteArray, dati: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, chiaveToken(), GCMParameterSpec(128, iv)) }
        return c.doFinal(dati).toString(Charsets.UTF_8)
    }

    private fun chiaveToken(): SecretKey {
        (ks.getKey(TOKEN, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(TOKEN, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun genera(alias: String, sfida: ByteArray?, opzioni: KeyGenParameterSpec.Builder.() -> Unit) {
        fun spec(strongBox: Boolean, conSfida: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .apply(opzioni)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .apply { if (conSfida && sfida != null) setAttestationChallenge(sfida) }
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        // StrongBox se c'è; l'attestazione se il telefono la sa fare (alcuni non la danno con StrongBox)
        for ((strongBox, conSfida) in listOf(true to true, false to true, false to false)) {
            try {
                kpg.initialize(spec(strongBox, conSfida))
                kpg.generateKeyPair()
                return
            } catch (_: StrongBoxUnavailableException) {
                continue
            } catch (e: java.security.ProviderException) {
                if (!conSfida || sfida == null) throw e  // senza attestazione non c'è altro da provare
            }
        }
        error("impossibile creare la chiave $alias")
    }

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TIMBRATURE = "timbro.timbrature"
        const val ADMIN = "timbro.admin"
        const val TLS = "timbro.tls"
        const val TOKEN = "timbro.token"
    }
}

package it.zawardo.timbro.core

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Richiesta di certificato PKCS#10 (RFC 2986) per la chiave TLS del telefono, costruita a mano in DER: è piccola e
 * così l'app non si porta dietro BouncyCastle. La firma la fa chi chiama ([firma]: SHA256withECDSA con la chiave del
 * Keystore, DER), quindi la chiave privata non esce dal chip.
 *
 * Il server (timbro/enroll.py) ne usa solo la chiave pubblica e controlla la firma, che prova il possesso della
 * chiave; il soggetto lo decide lui (CN = id del telefono). Il nostro CN serve solo a riconoscerla nei log.
 */
object Csr {
    private val OID_CN = byteArrayOf(0x55, 0x04, 0x03)  // 2.5.4.3
    private val OID_O = byteArrayOf(0x55, 0x04, 0x0A)   // 2.5.4.10
    private val OID_ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)  // 1.2.840.10045.4.3.2

    /**
     * [spki] = la chiave pubblica in DER SubjectPublicKeyInfo (`PublicKey.encoded`); [firma] riceve i byte di
     * CertificationRequestInfo e restituisce la firma ECDSA in DER.
     */
    fun der(spki: ByteArray, nome: String, organizzazione: String = "Timbro", firma: (ByteArray) -> ByteArray): ByteArray {
        require(nome.isNotBlank()) { "serve un nome" }
        val soggetto = seq(
            set(seq(tlv(0x06, OID_O), tlv(0x0C, organizzazione.toByteArray(Charsets.UTF_8)))),
            set(seq(tlv(0x06, OID_CN), tlv(0x0C, nome.toByteArray(Charsets.UTF_8)))),
        )
        val info = seq(
            tlv(0x02, byteArrayOf(0)),  // version 0
            soggetto,
            spki,                        // già DER
            tlv(0xA0, ByteArray(0)),     // attributes [0] IMPLICIT SET OF, vuoto
        )
        val algoritmo = seq(tlv(0x06, OID_ECDSA_SHA256))
        val bitString = tlv(0x03, byteArrayOf(0) + firma(info))
        return seq(info, algoritmo, bitString)
    }

    fun pem(der: ByteArray): String = Pem.codifica("CERTIFICATE REQUEST", der)

    internal fun tlv(tag: Int, valore: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = valore.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> error("DER troppo lungo")
        }
        out.write(valore)
        return out.toByteArray()
    }

    private fun seq(vararg parti: ByteArray) = tlv(0x30, parti.fold(ByteArray(0)) { acc, b -> acc + b })
    private fun set(vararg parti: ByteArray) = tlv(0x31, parti.fold(ByteArray(0)) { acc, b -> acc + b })
}

/** PEM a 64 colonne, come lo scrive openssl. */
object Pem {
    fun codifica(etichetta: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        return buildString {
            append("-----BEGIN $etichetta-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END $etichetta-----\n")
        }
    }

    /** Tutti i blocchi di un testo PEM (es. una catena di certificati), in ordine. */
    fun blocchi(testo: String): List<ByteArray> =
        Regex("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", RegexOption.DOT_MATCHES_ALL).findAll(testo)
            .map { Base64.getMimeDecoder().decode(it.groupValues[2].trim()) }.toList()
}

package it.zawardo.timbro.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il CSR costruito a mano lo legge e lo verifica una libreria indipendente (BouncyCastle, solo nei test). */
class CsrTest {
    private fun coppia() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun `CSR valido e firmato dalla chiave che contiene`() {
        val k = coppia()
        val der = Csr.der(k.public.encoded, "Pixel di prova (5b0f6c1e)") { info ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(k.private); update(info) }.sign()
        }
        val csr = PKCS10CertificationRequest(der)
        assertTrue(csr.isSignatureValid(JcaContentVerifierProviderBuilder().setProvider(BouncyCastleProvider()).build(csr.subjectPublicKeyInfo)))
        assertArrayEquals(k.public.encoded, csr.subjectPublicKeyInfo.encoded)
        assertEquals("Pixel di prova (5b0f6c1e)", csr.subject.getRDNs(BCStyle.CN)[0].first.value.toString())
        assertEquals("Timbro", csr.subject.getRDNs(BCStyle.O)[0].first.value.toString())
        assertEquals("1.2.840.10045.4.3.2", csr.signatureAlgorithm.algorithm.id)
        assertTrue(csr.attributes.isEmpty())
        // PEM letto di nuovo uguale
        val pem = Csr.pem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        assertArrayEquals(der, Pem.blocchi(pem).single())
    }

    @Test
    fun `firma con un'altra chiave rifiutata`() {
        val k = coppia()
        val altra = coppia()
        val der = Csr.der(k.public.encoded, "x") { info ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(altra.private); update(info) }.sign()
        }
        val csr = PKCS10CertificationRequest(der)
        assertFalse(csr.isSignatureValid(JcaContentVerifierProviderBuilder().setProvider(BouncyCastleProvider()).build(csr.subjectPublicKeyInfo)))
    }

    @Test
    fun `lunghezze DER lunghe e catene PEM`() {
        assertArrayEquals(byteArrayOf(0x04, 0x81.toByte(), 200.toByte()), Csr.tlv(0x04, ByteArray(200)).copyOf(3))
        assertArrayEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x2C), Csr.tlv(0x04, ByteArray(300)).copyOf(4))
        val catena = Pem.codifica("CERTIFICATE", byteArrayOf(1, 2, 3)) + Pem.codifica("CERTIFICATE", ByteArray(100) { it.toByte() })
        assertEquals(listOf(3, 100), Pem.blocchi(catena).map { it.size })
    }
}

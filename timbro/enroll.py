"""Certificati client per mTLS, rilasciati all'associazione del telefono (POST /api/v1/enroll).

Il telefono crea nel chip sicuro (AndroidKeyStore) la chiave per TLS e manda una richiesta di certificato (PKCS#10,
CSR) insieme al codice di associazione. Il server la firma con la CA intermedia di Timbro (file in
CLIENT_CA_CERT_FILE e CLIENT_CA_KEY_FILE, montati in sola lettura) e restituisce certificato e catena. La chiave
privata non lascia mai il telefono: niente .p12, niente password, niente file da copiare a mano.

nginx (timbro-api) si fida della radice di Timbro e accetta catene di profondità 2 (radice -> intermedia -> telefono).
mTLS resta una barriera in più: token e firme restano obbligatori (CLAUDE.md).

Dal CSR conta solo la chiave pubblica (e la firma, che prova il possesso della chiave privata): soggetto, durata ed
estensioni li decide il server.
"""

import hashlib
from datetime import timedelta
from functools import cache
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from timbro.db import utcnow

MAX_CSR_BYTES = 8000


class EnrollError(ValueError):
    """Richiesta di certificato non accettabile (422)."""


class NotConfigured(RuntimeError):
    """CA dei certificati client non configurata sul server (503)."""


@cache
def _ca(cert_file: str, key_file: str) -> tuple[x509.Certificate, object, str]:
    cert_pem = Path(cert_file).read_bytes()
    cert = x509.load_pem_x509_certificate(cert_pem)
    key = serialization.load_pem_private_key(Path(key_file).read_bytes(), password=None)
    if cert.public_key().public_numbers() != key.public_key().public_numbers():
        raise NotConfigured("la chiave della CA client non corrisponde al certificato")
    return cert, key, cert_pem.decode()


def load_ca(settings) -> tuple[x509.Certificate, object, str]:
    if not (settings.client_ca_cert_file and settings.client_ca_key_file):
        raise NotConfigured("certificati client non configurati sul server (CLIENT_CA_CERT_FILE, CLIENT_CA_KEY_FILE)")
    try:
        return _ca(str(settings.client_ca_cert_file), str(settings.client_ca_key_file))
    except (OSError, ValueError) as e:
        raise NotConfigured(f"CA client illeggibile: {type(e).__name__}") from e


def parse_csr(csr_pem: str) -> x509.CertificateSigningRequest:
    """CSR valido, firmato dalla chiave che contiene, con una chiave EC P-256/P-384 o RSA di almeno 2048 bit."""
    if len(csr_pem) > MAX_CSR_BYTES:
        raise EnrollError("richiesta di certificato troppo grande")
    try:
        csr = x509.load_pem_x509_csr(csr_pem.encode())
    except ValueError as e:
        raise EnrollError("richiesta di certificato illeggibile") from e
    if not csr.is_signature_valid:
        raise EnrollError("firma della richiesta di certificato non valida")
    key = csr.public_key()
    ok = (isinstance(key, ec.EllipticCurvePublicKey) and key.curve.name in ("secp256r1", "secp384r1")) or (
        isinstance(key, rsa.RSAPublicKey) and key.key_size >= 2048
    )
    if not ok:
        raise EnrollError("chiave della richiesta di certificato non ammessa (EC P-256/P-384 o RSA >= 2048)")
    return csr


def issue(csr: x509.CertificateSigningRequest, device_id: str, settings) -> tuple[str, str, str]:
    """(certificato PEM, catena PEM fino all'intermedia, impronta SHA-256 del certificato)."""
    ca_cert, ca_key, ca_pem = load_ca(settings)
    now = utcnow()
    not_after = min(now + timedelta(days=settings.client_cert_days), ca_cert.not_valid_after_utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Timbro"),
            x509.NameAttribute(NameOID.COMMON_NAME, device_id),
        ]))
        .issuer_name(ca_cert.subject)
        .public_key(csr.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                     data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                     crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(csr.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_cert.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    der = cert.public_bytes(serialization.Encoding.DER)
    pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    return pem, pem + ca_pem, hashlib.sha256(der).hexdigest()

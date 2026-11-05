"""Associazione con certificato client (POST /api/v1/enroll): CSR firmato dalla CA intermedia di Timbro."""

from datetime import UTC, datetime, timedelta

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from fastapi.testclient import TestClient

from tests.helpers import Phone
from timbro import services
from timbro.app import create_app
from timbro.config import Settings
from timbro.models import Device


def _name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def make_ca(directory):
    """Radice e intermedia di prova, come sul server (la radice resta fuori dall'app)."""
    now = datetime.now(UTC)
    root_key = ec.generate_private_key(ec.SECP384R1())
    root = (x509.CertificateBuilder().subject_name(_name("Timbro root")).issuer_name(_name("Timbro root"))
            .public_key(root_key.public_key()).serial_number(1).not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=True, path_length=1), critical=True).sign(root_key, hashes.SHA256()))
    inter_key = ec.generate_private_key(ec.SECP256R1())
    inter = (x509.CertificateBuilder().subject_name(_name("Timbro client CA")).issuer_name(root.subject)
             .public_key(inter_key.public_key()).serial_number(2).not_valid_before(now - timedelta(days=1))
             .not_valid_after(now + timedelta(days=1825))
             .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True).sign(root_key, hashes.SHA256()))
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "ca.crt").write_bytes(inter.public_bytes(serialization.Encoding.PEM))
    (directory / "ca.key").write_bytes(inter_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return root, inter


def csr_for(key, cn="qualsiasi"):
    return (x509.CertificateSigningRequestBuilder().subject_name(_name(cn)).sign(key, hashes.SHA256())
            .public_bytes(serialization.Encoding.PEM).decode())


@pytest.fixture
def env(tmp_path, database_url):
    root, inter = make_ca(tmp_path / "ca")
    settings = Settings(database_url=database_url, data_dir=tmp_path / "dati", worker_enabled=False,
                        attestation_required=False, _env_file=None,
                        client_ca_cert_file=tmp_path / "ca" / "ca.crt", client_ca_key_file=tmp_path / "ca" / "ca.key")
    with TestClient(create_app(settings)) as client:
        yield client, root, inter


def code_for(client):
    with client.app.state.db.session() as s:
        return services.new_pairing_code(s, services.create_user(s, f"u{datetime.now().timestamp()}"), client.app.state.settings)


def test_associazione_con_certificato(env):
    client, root, inter = env
    ph, tls = Phone(), ec.generate_private_key(ec.SECP256R1())
    code = code_for(client)
    r = client.post("/api/v1/enroll", json={**ph.registration(code, "Pixel"), "csr_pem": csr_for(tls, "ignorato")})
    assert r.status_code == 200, r.text
    out = r.json()
    cert = x509.load_pem_x509_certificate(out["client_cert_pem"].encode())
    chain = x509.load_pem_x509_certificates(out["client_chain_pem"].encode())
    assert chain[0] == cert and chain[1] == inter
    # firmato dall'intermedia, per la chiave del telefono, con il soggetto deciso dal server
    cert.verify_directly_issued_by(inter)
    inter.verify_directly_issued_by(root)
    assert cert.public_key().public_numbers() == tls.public_key().public_numbers()
    assert cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value == out["device_id"]
    assert cert.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value == x509.ExtendedKeyUsage(
        [ExtendedKeyUsageOID.CLIENT_AUTH])
    assert cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca is False
    assert cert.not_valid_after_utc - datetime.now(UTC) > timedelta(days=700)
    with client.app.state.db.session() as s:
        assert s.get(Device, out["device_id"]).client_cert_sha256
    # il token funziona come con /devices/register
    ph.device_id, ph.token = out["device_id"], out["api_token"]
    assert client.get("/api/v1/me", headers=ph.auth).status_code == 200


def test_richieste_rifiutate_senza_consumare_il_codice(env):
    client, *_ = env
    code = code_for(client)
    ph = Phone()
    bad = {
        "illeggibile": "-----BEGIN CERTIFICATE REQUEST-----\nAAAA\n-----END CERTIFICATE REQUEST-----\n",
        "chiave debole": csr_for(rsa.generate_private_key(public_exponent=65537, key_size=1024)),
        "curva non ammessa": csr_for(ec.generate_private_key(ec.SECP256K1())),
    }
    for name, csr in bad.items():
        assert client.post("/api/v1/enroll", json={**ph.registration(code), "csr_pem": csr}).status_code == 422, name
    # il codice è ancora buono
    r = client.post("/api/v1/enroll", json={**ph.registration(code), "csr_pem": csr_for(ec.generate_private_key(ec.SECP256R1()))})
    assert r.status_code == 200
    # e poi è consumato
    r = client.post("/api/v1/enroll", json={**Phone().registration(code), "csr_pem": csr_for(ec.generate_private_key(ec.SECP256R1()))})
    assert r.status_code == 403


def test_firma_del_csr_controllata():
    """Un CSR con la chiave di un altro (firma che non torna) non passa: prova di possesso della chiave."""
    from timbro import enroll

    a, b = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    good = x509.load_pem_x509_csr(csr_for(a).encode())
    forged_tbs = (x509.CertificateSigningRequestBuilder().subject_name(_name("x")).sign(b, hashes.SHA256()))
    assert enroll.parse_csr(csr_for(a)).public_key().public_numbers() == good.public_key().public_numbers()
    # firma alterata: si cambia un byte della firma
    der = bytearray(forged_tbs.public_bytes(serialization.Encoding.DER))
    der[-5] ^= 0x01
    tampered = x509.load_der_x509_csr(bytes(der)).public_bytes(serialization.Encoding.PEM).decode()
    with pytest.raises(enroll.EnrollError, match="firma"):
        enroll.parse_csr(tampered)


def test_senza_ca_configurata(tmp_path, database_url):
    settings = Settings(database_url=database_url, data_dir=tmp_path, worker_enabled=False, _env_file=None)
    with TestClient(create_app(settings)) as client:
        code = code_for(client)
        r = client.post("/api/v1/enroll", json={**Phone().registration(code), "csr_pem": csr_for(ec.generate_private_key(ec.SECP256R1()))})
        assert r.status_code == 503
        # il codice non è stato consumato: si può associare con /devices/register (o riprovare dopo)
        assert client.post("/api/v1/devices/register", json=Phone().registration(code)).status_code == 200

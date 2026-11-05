"""CA di sviluppo per /api/v1/enroll sul PC: radice e intermedia in local-data/ca (ignorata da git).

    python tests/scripts/ca_sviluppo.py
poi nel .env del PC: CLIENT_CA_CERT_FILE e CLIENT_CA_KEY_FILE (percorsi visti dal container: /data/ca/...).
Mai sul server: lì la CA intermedia si crea con la radice vera (docs/PROGETTO.md §22).
"""

from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

out = Path(__file__).resolve().parents[2] / "local-data" / "ca"
out.mkdir(parents=True, exist_ok=True)
now = datetime.now(UTC)


def name(cn):
    return x509.Name([x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Timbro sviluppo"), x509.NameAttribute(NameOID.COMMON_NAME, cn)])


root_key = ec.generate_private_key(ec.SECP384R1())
root = (x509.CertificateBuilder().subject_name(name("Timbro sviluppo radice")).issuer_name(name("Timbro sviluppo radice"))
        .public_key(root_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=1), critical=True).sign(root_key, hashes.SHA256()))
key = ec.generate_private_key(ec.SECP256R1())
inter = (x509.CertificateBuilder().subject_name(name("Timbro sviluppo client CA")).issuer_name(root.subject)
         .public_key(key.public_key()).serial_number(x509.random_serial_number())
         .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=1825))
         .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True).sign(root_key, hashes.SHA256()))
(out / "root.crt").write_bytes(root.public_bytes(serialization.Encoding.PEM))
(out / "client-ca.crt").write_bytes(inter.public_bytes(serialization.Encoding.PEM))
(out / "client-ca.key").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                       serialization.NoEncryption()))
print(f"CA di sviluppo in {out}")

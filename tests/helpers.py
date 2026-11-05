"""Telefono simulato: chiavi P-256, manifest e richieste admin firmati come farà l'app."""

import base64
import hashlib
import json
import os
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


class Phone:
    """Simula il telefono: chiave P-256 e manifest firmati come farà l'app."""

    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.pem = self.key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        ).decode()
        # chiave admin: sul telefono vero è utilizzabile solo dopo l'impronta digitale
        self.admin_key = ec.generate_private_key(ec.SECP256R1())
        self.admin_pem = self.admin_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        ).decode()
        self.device_id = None
        self.token = None

    def registration(self, code: str, name: str = "telefono") -> dict:
        return {"pairing_code": code, "name": name, "public_key_pem": self.pem, "admin_public_key_pem": self.admin_pem}

    def admin_headers(self, method: str, url: str, content: bytes = b"", key=None, time=None, nonce=None) -> dict:
        """Intestazioni di una richiesta admin firmata, come le produrrà l'app."""
        from datetime import UTC, datetime
        from urllib.parse import urlsplit

        from timbro.api.admin_auth import canonical

        parts = urlsplit(url)
        time = time or datetime.now(UTC).isoformat()
        nonce = nonce or base64.urlsafe_b64encode(os.urandom(18)).decode()
        msg = canonical(method, parts.path, parts.query, time, nonce, content)
        sig = (key or self.admin_key).sign(msg, ec.ECDSA(hashes.SHA256()))
        return {
            **self.auth,
            "X-Timbro-Time": time,
            "X-Timbro-Nonce": nonce,
            "X-Timbro-Signature": base64.b64encode(sig).decode(),
        }

    def admin(self, client, method: str, url: str, json_body=None, **kw):
        content = json.dumps(json_body).encode() if json_body is not None else b""
        headers = self.admin_headers(method, url, content, **kw)
        if json_body is not None:
            headers["Content-Type"] = "application/json"
        return client.request(method, url, content=content, headers=headers)

    def punch(self, video: bytes, kind="in", client_id=None, **extra):
        m = {
            "v": 1,
            "client_id": client_id or str(uuid.uuid4()),
            "device_id": self.device_id,
            "kind": kind,
            "sha256": hashlib.sha256(video).hexdigest(),
            "size": len(video),
            "duration_ms": 8000,
            "device_time": datetime.now(ZoneInfo("Europe/Rome")).isoformat(timespec="milliseconds"),
            "location": {"lat": 41.9, "lon": 12.5, "accuracy_m": 10, "provider": "fused", "is_mock": False},
            "app_version": "0.1.0",
            **extra,
        }
        if "clocks" not in extra:  # telefono onesto: l'orologio di rete coincide con l'ora del telefono
            m["clocks"] = {"auto_time": True, "network": m["device_time"]}
        raw = json.dumps(m, separators=(",", ":")).encode()
        return raw, self.key.sign(raw, ec.ECDSA(hashes.SHA256()))

    @property
    def auth(self):
        return {"Authorization": f"Bearer {self.token}"}


def body(raw: bytes, sig: bytes, attestation: list[str] | None = None) -> dict:
    b = {"manifest": base64.b64encode(raw).decode(), "signature": base64.b64encode(sig).decode()}
    if attestation is not None:
        b["attestation"] = attestation
    return b


# --- attestazione hardware finta (stessa struttura di Android Key Attestation, radice di prova) ----------------------


def der(tag: int, content: bytes, cls: int = 0, constructed: bool = False) -> bytes:
    flags = (cls << 6) | (0x20 if constructed else 0)
    if tag < 31:
        head = bytes([flags | tag])
    else:
        parts = [tag & 0x7F]
        tag >>= 7
        while tag:
            parts.append(0x80 | (tag & 0x7F))
            tag >>= 7
        head = bytes([flags | 0x1F]) + bytes(reversed(parts))
    n = len(content)
    length = bytes([n]) if n < 128 else bytes([0x80 | ((n.bit_length() + 7) // 8)]) + n.to_bytes((n.bit_length() + 7) // 8, "big")
    return head + length + content


def SEQ(*xs):
    return der(16, b"".join(xs), constructed=True)


def SET(*xs):
    return der(17, b"".join(sorted(xs)), constructed=True)


def INT(v):
    return der(2, v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big", signed=True))


def ENUM(v):
    return der(10, bytes([v]))


def OCT(b):
    return der(4, b)


def BOOL(v):
    return der(1, b"\xff" if v else b"\x00")


def EXP(tag, inner):
    return der(tag, inner, cls=2, constructed=True)


def key_description(
    challenge: bytes, level=1, locked=True, boot=0, package="it.test.timbro", digest=b"\x11" * 32, keymint_level=None,
    version=300,
) -> bytes:
    app_id = SEQ(SET(SEQ(OCT(package.encode()), INT(1))), SET(OCT(digest)))
    sw = SEQ(EXP(701, INT(1_790_000_000_000)), EXP(709, OCT(app_id)))
    rot = SEQ(OCT(b"\x22" * 32), BOOL(locked), ENUM(boot), OCT(b"\x33" * 32))
    hw = SEQ(EXP(1, SET(INT(2))), EXP(2, INT(3)), EXP(704, rot), EXP(705, INT(160000)), EXP(706, INT(202610)))
    keymint_level = level if keymint_level is None else keymint_level
    return SEQ(INT(version), ENUM(level), INT(300), ENUM(keymint_level), OCT(challenge), OCT(b""), sw, hw)


class AttestationCA:
    """Radice e intermedio di prova; la radice va in `directory` (al posto delle radici di Google)."""

    def __init__(self, directory, factory=False, inter_days=(-1, 30)):
        """factory=True: intermedio con serialNumber nel soggetto, come le chiavi installate in fabbrica."""
        from datetime import UTC, datetime, timedelta

        from cryptography import x509
        from cryptography.x509.oid import NameOID

        self.x509, self.NameOID = x509, NameOID
        now = datetime.now(UTC)
        self.root_key = ec.generate_private_key(ec.SECP384R1())
        self.inter_key = ec.generate_private_key(ec.SECP256R1())
        self.root = self._cert("radice di prova", self.root_key.public_key(), "radice di prova", self.root_key, True,
                               now - timedelta(days=10), now + timedelta(days=3650))
        self.inter = self._cert("intermedio di prova", self.inter_key.public_key(), "radice di prova", self.root_key, True,
                                now + timedelta(days=inter_days[0]), now + timedelta(days=inter_days[1]),
                                serial_attr="f92009e853b6b045" if factory else None)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "radice-di-prova.pem").write_bytes(self.root.public_bytes(serialization.Encoding.PEM))

    def _cert(self, subject, pub, issuer, signer, ca, start, end, ext=None, serial_attr=None):
        x509, NameOID = self.x509, self.NameOID
        attrs = [x509.NameAttribute(NameOID.COMMON_NAME, subject)]
        if serial_attr:
            attrs.append(x509.NameAttribute(NameOID.SERIAL_NUMBER, serial_attr))
        b = (
            x509.CertificateBuilder()
            .subject_name(x509.Name(attrs))
            .issuer_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, issuer)]))
            .public_key(pub)
            .serial_number(x509.random_serial_number())
            .not_valid_before(start)
            .not_valid_after(end)
            .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
        )
        if ext is not None:
            b = b.add_extension(x509.UnrecognizedExtension(x509.ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), ext), critical=False)
        return b.sign(signer, hashes.SHA256())

    def chain(self, manifest_raw: bytes | None = None, challenge: bytes | None = None, **kd) -> list[str]:
        from datetime import UTC, datetime

        challenge = challenge if challenge is not None else hashlib.sha256(manifest_raw).digest()
        leaf_key = self.leaf_key = ec.generate_private_key(ec.SECP256R1())
        # date "convenzionali" come su Android: la validità della foglia non conta
        leaf = self._cert("Android Keystore Key", leaf_key.public_key(), "intermedio di prova", self.inter_key, False,
                          datetime(1970, 1, 1, tzinfo=UTC), datetime(2048, 1, 1, tzinfo=UTC), key_description(challenge, **kd))
        return [base64.b64encode(c.public_bytes(serialization.Encoding.DER)).decode() for c in (leaf, self.inter, self.root)]


def give_schedule(client, ph, schedules: dict, name: str = "Orario di prova") -> tuple[int, int]:
    """Come l'amministratore: rende admin l'utente del telefono, crea l'articolazione con le versioni di schedules e
    gliela assegna dalla prima data. Restituisce (articolazione, utente)."""
    from timbro import accounts
    from timbro.models import Device

    with client.app.state.db.session() as s:
        uid = s.get(Device, ph.device_id).user_id
        accounts.set_role(s, uid, "admin")
    versions = schedules["versions"]
    r = ph.admin(client, "POST", "/api/v1/admin/system/schedules", {"name": name, "version": versions[0]})
    assert r.status_code == 200, r.text
    sid = r.json()["id"]
    for v in versions[1:]:
        r = ph.admin(client, "PUT", f"/api/v1/admin/system/schedules/{sid}/versions", v)
        assert r.status_code == 200, r.text
    r = ph.admin(client, "PUT", f"/api/v1/admin/system/users/{uid}/assignments",
                 [{"schedule_id": sid, "valid_from": versions[0]["valid_from"]}])
    assert r.status_code == 200, r.text
    return sid, uid

"""Attestazione hardware di ogni timbratura (Android Key Attestation).

A ogni timbratura l'app genera nell'Android Keystore una chiave usa e getta con challenge = SHA-256 dei byte del
manifest, e manda la catena di certificati che Android rilascia per quella chiave. La catena risale alle radici
di Google (attestation_roots/, fissate per impronta della chiave pubblica) e il certificato foglia contiene
l'estensione KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17) che dice, in quel momento:

- che la chiave sta nell'hardware sicuro (TEE o StrongBox);
- se il bootloader era bloccato e l'avvio verificato (rootOfTrust): il root ottenuto sbloccando il bootloader non
  passa. Restano fuori il root da una vulnerabilità a bootloader bloccato e le chiavi di attestazione trapelate,
  finché Google non le revoca (lista di revoca, controllata ogni giorno);
- quale app l'ha chiesta (nome del pacchetto e impronta del certificato di firma dell'APK).

La challenge lega l'attestazione a quel manifest (posizione, reti Wi-Fi, ora del telefono compresi): niente
riuso per un'altra timbratura. La timbratura si accetta sempre (la prova non si perde); l'esito si conserva e,
se non va, problems.py lo segnala.

Riferimenti: https://source.android.com/docs/security/features/keystore/attestation e
https://developer.android.com/privacy-and-security/security-key-attestation
"""

import base64
import hashlib
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

from asn1crypto import pem
from asn1crypto import x509 as asn1_x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
SECURITY_LEVELS = {0: "software", 1: "tee", 2: "strongbox"}
BOOT_STATES = {0: "verified", 1: "self-signed", 2: "unverified", 3: "failed"}
MAX_CERTS = 10
MAX_CERT_BYTES = 16 * 1024

# Etichette (EXPLICIT, classe context) dell'AuthorizationList che servono qui
TAG_ROOT_OF_TRUST = 704
TAG_OS_VERSION = 705
TAG_OS_PATCH_LEVEL = 706
TAG_APPLICATION_ID = 709


# --- DER minimo (lettura) -------------------------------------------------------------------------------------------


def der_read(data: bytes, pos: int = 0) -> tuple[int, bool, int, bytes, int]:
    """Un elemento TLV: (classe, costruito, etichetta, valore, posizione successiva). Etichette alte comprese."""
    first = data[pos]
    pos += 1
    cls, constructed, tag = first >> 6, bool(first & 0x20), first & 0x1F
    if tag == 0x1F:
        tag = 0
        while True:
            b = data[pos]
            pos += 1
            tag = (tag << 7) | (b & 0x7F)
            if not b & 0x80:
                break
    length = data[pos]
    pos += 1
    if length & 0x80:
        n = length & 0x7F
        if not 1 <= n <= 4:
            raise ValueError("lunghezza DER non valida")
        length = int.from_bytes(data[pos : pos + n], "big")
        pos += n
    end = pos + length
    if end > len(data):
        raise ValueError("DER troncato")
    return cls, constructed, tag, data[pos:end], end


def der_children(data: bytes) -> list[tuple[int, bool, int, bytes]]:
    out, pos = [], 0
    while pos < len(data):
        cls, constructed, tag, value, pos = der_read(data, pos)
        out.append((cls, constructed, tag, value))
    return out


def _int(value: bytes) -> int:
    """INTEGER DER. Al massimo 64 bit (più il byte di segno): nell'attestazione non ce ne sono di più grandi, e un
    intero enorme farebbe fallire json.dumps dell'esito."""
    if not 1 <= len(value) <= 9:
        raise ValueError("intero fuori misura nell'attestazione")
    return int.from_bytes(value, "big", signed=True)


def _authorizations(seq_value: bytes) -> dict[int, bytes]:
    """AuthorizationList: {etichetta: DER dell'elemento interno}."""
    out = {}
    for cls, _, tag, value in der_children(seq_value):
        if cls == 2:  # context-specific, EXPLICIT
            out[tag] = value
    return out


@dataclass
class KeyDescription:
    attestation_version: int
    security_level: str
    keymint_security_level: str
    challenge: bytes
    device_locked: bool | None = None
    verified_boot_state: str | None = None
    verified_boot_key: str | None = None
    os_version: int | None = None
    os_patch_level: int | None = None
    packages: list[tuple[str, int]] = field(default_factory=list)
    signature_digests: list[str] = field(default_factory=list)


def parse_key_description(ext_value: bytes) -> KeyDescription:
    _, _, _, seq, _ = der_read(ext_value)
    items = der_children(seq)
    if len(items) < 8:
        raise ValueError("KeyDescription incompleta")
    sw, hw = _authorizations(items[6][3]), _authorizations(items[7][3])
    kd = KeyDescription(
        attestation_version=_int(items[0][3]),
        security_level=SECURITY_LEVELS.get(_int(items[1][3]), "?"),
        keymint_security_level=SECURITY_LEVELS.get(_int(items[3][3]), "?"),
        challenge=items[4][3],
    )
    # rootOfTrust ha senso solo se garantito dall'hardware
    if TAG_ROOT_OF_TRUST in hw:
        _, _, _, rot, _ = der_read(hw[TAG_ROOT_OF_TRUST])
        r = der_children(rot)
        kd.verified_boot_key = r[0][3].hex()
        kd.device_locked = r[1][3] != b"\x00"
        kd.verified_boot_state = BOOT_STATES.get(_int(r[2][3]), "?")
    for tag, attr in ((TAG_OS_VERSION, "os_version"), (TAG_OS_PATCH_LEVEL, "os_patch_level")):
        if tag in hw:
            setattr(kd, attr, _int(der_read(hw[tag])[3]))
    app_id = sw.get(TAG_APPLICATION_ID) or hw.get(TAG_APPLICATION_ID)
    if app_id:
        _, _, _, octets, _ = der_read(app_id)  # OCTET STRING che contiene il DER di AttestationApplicationId
        _, _, _, aid, _ = der_read(octets)
        infos, digests = der_children(aid)[:2]
        for _, _, _, info in der_children(infos[3]):
            name, version = der_children(info)[:2]
            kd.packages.append((name[3].decode("utf-8", "replace"), _int(version[3])))
        kd.signature_digests = [d[3].hex() for d in der_children(digests[3])]
    return kd


# --- verifica -------------------------------------------------------------------------------------------------------


@dataclass
class Result:
    ok: bool
    problems: list[str]
    root: str | None = None  # quale radice di Google (impronta SHA-256 della chiave pubblica)
    security_level: str | None = None
    device_locked: bool | None = None
    verified_boot_state: str | None = None
    os_version: int | None = None
    os_patch_level: int | None = None
    packages: list[list] = field(default_factory=list)
    signature_digests: list[str] = field(default_factory=list)
    serials: list[str] = field(default_factory=list)  # esadecimale minuscolo, come nella lista di revoca di Google
    challenge_ok: bool = False
    provisioning: str | None = None  # "fabbrica" (chiavi installate in fabbrica) o "remota" (RKP, certificati brevi)
    revoked: list[str] = field(default_factory=list)  # seriali della catena già nella lista di revoca di Google
    notes: list[str] = field(default_factory=list)  # osservazioni che non sono problemi

    def as_dict(self) -> dict:
        return asdict(self)


def _aware(dt: datetime) -> datetime:
    """GeneralizedTime senza fuso (codifica non canonica di alcuni telefoni): si legge come UTC."""
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt


def _hash(name: str):
    return {"sha1": hashes.SHA1(), "sha256": hashes.SHA256(), "sha384": hashes.SHA384(), "sha512": hashes.SHA512()}[name]


class Cert:
    """Certificato letto con asn1crypto, che tollera le codifiche non canoniche di alcuni telefoni (es. parametri
    NULL nell'algoritmo ECDSA): cryptography li rifiuta. Le firme si verificano con cryptography sui byte originali."""

    def __init__(self, der: bytes):
        self.der = der
        self.asn = asn1_x509.Certificate.load(der)
        tbs = self.asn["tbs_certificate"]
        self.tbs = tbs
        self.serial = format(self.asn.serial_number, "x")
        self.spki_der = tbs["subject_public_key_info"].dump()
        self.subject = tbs["subject"].dump()
        self.issuer = tbs["issuer"].dump()
        self.not_before = _aware(tbs["validity"]["not_before"].native)
        self.not_after = _aware(tbs["validity"]["not_after"].native)

    @property
    def spki_sha256(self) -> str:
        return hashlib.sha256(self.spki_der).hexdigest()

    def extensions(self, oid: str) -> list[bytes]:
        return [ext["extn_value"].contents for ext in self.tbs["extensions"] or [] if ext["extn_id"].dotted == oid]

    def extension(self, oid: str) -> bytes | None:
        found = self.extensions(oid)
        return found[0] if found else None

    def can_sign_certificates(self) -> bool:
        """Un emittente della catena deve poter firmare certificati: se dichiara basicConstraints deve essere una CA,
        se dichiara keyUsage deve avere keyCertSign. La foglia di una chiave del Keystore non lo è (solo
        digitalSignature): così non può fare da emittente di una foglia inventata."""
        bc = self.asn.basic_constraints_value
        if bc is not None and not bc["ca"].native:
            return False
        ku = self.asn.key_usage_value
        return ku is None or "key_cert_sign" in ku.native

    def verify_issued_by(self, issuer: "Cert") -> None:
        """Conta la firma, non il nome dell'emittente: alcune implementazioni StrongBox scrivono nella foglia un
        emittente diverso dal soggetto dell'intermedio che l'ha davvero firmata."""
        key = serialization.load_der_public_key(issuer.spki_der)
        algo = self.asn["signature_algorithm"]
        signature = self.asn["signature_value"].native
        tbs = self.tbs.dump()
        h = _hash(algo.hash_algo)
        if isinstance(key, ec.EllipticCurvePublicKey) and algo.signature_algo == "ecdsa":
            key.verify(signature, tbs, ec.ECDSA(h))
        elif isinstance(key, rsa.RSAPublicKey) and algo.signature_algo == "rsassa_pkcs1v15":
            key.verify(signature, tbs, padding.PKCS1v15(), h)
        elif isinstance(key, rsa.RSAPublicKey) and algo.signature_algo == "rsassa_pss":
            key.verify(signature, tbs, padding.PSS(padding.MGF1(h), padding.PSS.AUTO), h)
        else:
            raise ValueError(f"algoritmo di firma non gestito: {algo.signature_algo}")


def load_roots(directory: Path) -> dict[str, Cert]:
    """Radici fidate, per impronta della chiave pubblica (Google le ha riemesse con date diverse, stessa chiave)."""
    roots = {}
    for f in sorted(directory.glob("*.pem")):
        for _, _, der in pem.unarmor(f.read_bytes(), multiple=True):
            c = Cert(der)
            roots[c.spki_sha256] = c
    return roots


def decode_chain(items: list[str]) -> list[bytes]:
    """Catena inviata dall'app: certificati DER in base64, dalla foglia alla radice."""
    if not items or len(items) > MAX_CERTS:
        raise ValueError(f"la catena deve avere da 1 a {MAX_CERTS} certificati")
    out = []
    for item in items:
        der = base64.b64decode(item, validate=True)
        if len(der) > MAX_CERT_BYTES:
            raise ValueError("certificato troppo grande")
        Cert(der)  # deve almeno essere un certificato
        out.append(der)
    return out


def to_pem(chain: list[bytes]) -> str:
    """Testo PEM con i byte esatti ricevuti, conservato e messo nel pacchetto di prova così com'è (le marche "attest"
    ne coprono i byte)."""
    return "".join(pem.armor("CERTIFICATE", der).decode() for der in chain)


def _is_root(c: Cert, roots: dict[str, Cert]) -> bool:
    return c.spki_sha256 in roots


def _check_path(certs: list[Cert], roots: dict[str, Cert], times: list[datetime], r: Result) -> None:
    """Catena dalla foglia a una radice di Google, con le regole della libreria di Google (android/keyattestation):

    - una radice fidata non può essere la foglia, e se è in fondo alla catena fa solo da àncora: si controlla la
      firma del certificato sotto con la chiave fissata nel repo, non la firma del certificato radice;
    - sotto la radice servono almeno la foglia e un intermedio (i telefoni veri ne hanno 2-4);
    - ogni emittente deve poter firmare certificati e non deve portare l'estensione KeyDescription: chi estende
      una catena vera con una foglia inventata, firmata con una chiave attestata qualsiasi, viene fermato qui;
    - date degli intermedi: "non ancora valido" è sempre un problema; la scadenza solo per le catene RKP, perché le
      chiavi installate in fabbrica prima del 2021 hanno certificati scaduti che Google dice di accettare.
    """
    problems = r.problems
    if _is_root(certs[0], roots):
        problems.append("la foglia è una radice di Google")
        return
    anchor = roots.get(certs[-1].spki_sha256)
    path = certs[:-1] if anchor is not None else certs
    if any(_is_root(c, roots) for c in path[1:]):
        problems.append("radice di Google in mezzo alla catena")
        return
    if len(path) < 2:
        problems.append("catena troppo corta: servono la foglia e almeno un intermedio sotto la radice")
        return
    for c in path[1:]:
        if c.extensions(KEY_DESCRIPTION_OID):
            problems.append(f"certificato {c.serial}: KeyDescription fuori dalla foglia (catena estesa)")
        if not c.can_sign_certificates():
            problems.append(f"certificato {c.serial}: non è un'autorità che può firmare certificati")
    for cert, issuer in zip(path, path[1:]):
        cert.verify_issued_by(issuer)
    if anchor is None:
        for candidate in roots.values():
            try:
                path[-1].verify_issued_by(candidate)
                anchor = candidate
                break
            except Exception:
                continue
        if anchor is None:
            problems.append("la catena non risale a una radice di Google")
            return
    else:
        path[-1].verify_issued_by(anchor)
    r.root = anchor.spki_sha256

    factory = "serial_number" in path[-1].asn.subject.native
    r.provisioning = "fabbrica" if factory else "remota"
    for c in path[1:]:
        valid_at = [t for t in times if c.not_before <= t and (factory or t <= c.not_after)]
        if not valid_at:
            problems.append(f"certificato {c.serial} della catena fuori validità")
        elif valid_at[0] is not times[0]:
            r.notes.append(f"certificato {c.serial} scaduto alla ricezione, valido all'ora della timbratura")


def verify(
    chain_der: list[bytes],
    manifest_raw: bytes,
    roots: dict[str, Cert],
    now: datetime,
    package: str = "",
    signing_cert_sha256: list[str] | None = None,
    revoked_serials: set[str] | frozenset[str] = frozenset(),
    punch_time: datetime | None = None,
) -> Result:
    """Esito dell'attestazione. Mai un'eccezione: qualunque cosa arrivi diventa un problema nell'esito.

    punch_time (ora dichiarata nel manifest, già vincolata dalla challenge) serve alle timbrature rimaste in coda
    sul telefono: i certificati RKP durano pochi giorni e alla ricezione possono essere già scaduti. Conta solo se
    è nelle due settimane prima della ricezione."""
    try:
        return _verify(chain_der, manifest_raw, roots, now, package, signing_cert_sha256, revoked_serials, punch_time)
    except Exception as e:
        return Result(False, [f"verifica non riuscita: {type(e).__name__}: {e}"[:300]])


def _verify(chain_der, manifest_raw, roots, now, package, signing_cert_sha256, revoked_serials, punch_time) -> Result:
    problems: list[str] = []
    try:
        certs = [Cert(d) for d in chain_der]
    except Exception as e:
        return Result(False, [f"certificati illeggibili: {type(e).__name__}: {e}"[:300]])
    if not certs:
        return Result(False, ["catena vuota"])
    r = Result(False, problems, serials=[c.serial for c in certs])
    r.revoked = [c.serial for c in certs if c.serial in revoked_serials]
    if r.revoked:
        problems.append(f"chiave di attestazione revocata da Google (seriali {', '.join(r.revoked)})")

    # 1. catena fino a una radice di Google
    times = [now]
    if punch_time is not None and now - timedelta(days=14) <= punch_time < now:
        times.append(punch_time)
    try:
        _check_path(certs, roots, times, r)
    except Exception as e:
        problems.append(f"catena non valida: {type(e).__name__}: {e}"[:300])

    # 2. contenuto dell'attestazione: una sola KeyDescription, nella foglia
    try:
        found = certs[0].extensions(KEY_DESCRIPTION_OID)
        if len(found) != 1:
            raise ValueError("la foglia deve avere una e una sola estensione KeyDescription")
        kd = parse_key_description(found[0])
    except Exception as e:
        problems.append(f"attestazione illeggibile: {type(e).__name__}: {e}"[:300])
        return r
    r.security_level = kd.security_level
    r.device_locked, r.verified_boot_state = kd.device_locked, kd.verified_boot_state
    r.os_version, r.os_patch_level = kd.os_version, kd.os_patch_level
    r.packages = [list(p) for p in kd.packages]
    r.signature_digests = kd.signature_digests
    r.challenge_ok = kd.challenge == hashlib.sha256(manifest_raw).digest()
    if not r.challenge_ok:
        problems.append("la challenge non è l'impronta di questo manifest")
    if kd.security_level not in ("tee", "strongbox"):
        problems.append(f"chiave non protetta dall'hardware ({kd.security_level})")
    elif kd.keymint_security_level != kd.security_level:
        problems.append(f"livelli di sicurezza diversi: attestazione {kd.security_level}, "
                        f"chiave {kd.keymint_security_level}")
    if kd.device_locked is not True:
        problems.append("bootloader sbloccato (o stato non attestato dall'hardware)")
    if kd.verified_boot_state != "verified":
        problems.append(f"avvio non verificato ({kd.verified_boot_state})")
    # senza pacchetto e impronta configurati qualunque app, anche una copia ricompilata, passerebbe: lo si dice
    if not package:
        problems.append("app non verificata: ATTESTATION_PACKAGE non impostata sul server")
    elif package not in {p for p, _ in kd.packages}:
        problems.append(f"app diversa da {package}: {[p for p, _ in kd.packages]}")
    if not signing_cert_sha256:
        problems.append("firma dell'app non verificata: ATTESTATION_SIGNING_CERT_SHA256 non impostata sul server")
    else:
        expected = {d.lower().replace(":", "") for d in signing_cert_sha256}
        if not expected & set(kd.signature_digests):
            problems.append("l'app non è firmata con il certificato atteso")
    r.ok = not problems
    return r


def revoked_serials(status: dict) -> set[str]:
    """Seriali della lista di revoca di Google (https://android.googleapis.com/attestation/status). Una risposta
    senza "entries" è un errore, non "nessuna revoca"."""
    entries = status.get("entries") if isinstance(status, dict) else None
    if not isinstance(entries, dict):
        raise ValueError("lista di revoca senza entries")
    return {k.lower() for k in entries}


def check_revocations(serials_by_punch: dict[int, list[str]], status: dict) -> list[dict]:
    """Confronta i numeri di serie con la lista di revoca di Google."""
    revoked_serials(status)  # formato
    entries = {k.lower(): v for k, v in status["entries"].items()}
    hits = []
    for punch_id, serials in sorted(serials_by_punch.items()):
        for serial in serials:
            e = entries.get(serial)
            if e is not None:
                e = e if isinstance(e, dict) else {}
                hits.append({"punch_id": punch_id, "serial": serial, "status": e.get("status"), "reason": e.get("reason")})
    return hits

"""Marche temporali RFC 3161: richiesta, parsing e verifica contro root fissate.

Si usa asn1crypto e non rfc3161-client: le TSA reali (ACCV, Sectigo, DigiCert) mettono i
certificati del SignedData in ordine non canonico DER e rfc3161-client li rifiuta.
"""

import asyncio
import base64
import secrets
from dataclasses import dataclass
from datetime import datetime
from functools import cache
from pathlib import Path

import aiohttp
import httpx
from asn1crypto import algos, cms, core, pem, tsp, x509
from pyhanko.sign.validation.generic_cms import validate_tst_signed_data
from pyhanko_certvalidator import ValidationContext
from pyhanko_certvalidator.fetchers.aiohttp_fetchers import AIOHttpFetcherBackend

ROOTS_DIR = Path(__file__).parent / "roots"


@dataclass(frozen=True)
class Provider:
    name: str
    url: str
    qualified: bool
    # root fidate: (file in roots/ senza .pem, impronta SHA-256). L'impronta fissata fa accorgere
    # se un file in roots/ viene cambiato.
    roots: tuple[tuple[str, str], ...]
    # impronte dei certificati che la EU Trusted List registra come identità del servizio QTST
    # (vedi trusted_list.py); almeno uno deve comparire nel percorso di validazione del token
    tl_service_sha256: tuple[str, ...] = ()
    tl_territory: str | None = None
    # CA intermedie fissate (stesso formato di roots): usate per costruire il percorso se la TSA
    # nel token mette solo il proprio certificato (la validazione non scarica nulla dalla rete)
    intermediates: tuple[tuple[str, str], ...] = ()

    def __post_init__(self):
        if self.qualified and not self.tl_service_sha256:
            raise ValueError(f"{self.name}: un provider qualificato deve fissare le identità TL")


PROVIDERS: dict[str, Provider] = {
    p.name: p
    for p in [
        Provider(
            "accv",
            "http://tss.accv.es:8318/tsa",
            True,
            (
                # gerarchia attuale (a settembre 2026 l'endpoint firma ancora con TSA1 ACCV 2016)
                ("accv", "9a6ec012e1a7da9dbe34194d478ad7c0db1822fb071df12981496ed104384113"),
                # nuova gerarchia ISTEC 2024 (policy 1.3.6.1.4.1.8149.3.100.3.0), stesso endpoint:
                # pronte per quando ACCV cambierà firmatario
                ("accv-2024-rsa", "63c3080cc8bf63c94c741a70daef52b5274acfe083eb8cec29131ea209388c25"),
                ("accv-2024-ecc", "accadfeabb86ddfa7f2ba12ea3852bb90b3cd3dc39b58364c32de8a2abd06cbc"),
            ),
            (
                # TL ES: "Qualified timestamps issued by 'TSA1 ACCV 2016'", QTST granted dal 2019-12-23
                "2c8d4233f9bc80934e466f0c9f76411f65a5c1789830160b666859ef93de2566",
                # TL ES: 'Qualified time stamps issued by "ACCV RSA1 TSA"', QTST granted dal 2025-05-06
                "fdd5aadd52b5995ef4b4a933a789746d4559daf2f1a15a594bd151aa18d99939",
                # TL ES: 'Qualified time stamps issued by "ACCV ECC1 TSA"', QTST granted dal 2025-05-06
                "677c8f5f17696dcc96f592c8b02eb50ae21a7a1c970997d4a7feb93d094e9957",
            ),
            "ES",
            (
                # le CA dei servizi RSA1/ECC1 così come registrate nella TL (discendono dalle root 2024)
                ("accv-2024-rsa1-ca", "fdd5aadd52b5995ef4b4a933a789746d4559daf2f1a15a594bd151aa18d99939"),
                ("accv-2024-ecc1-ca", "677c8f5f17696dcc96f592c8b02eb50ae21a7a1c970997d4a7feb93d094e9957"),
            ),
        ),
        Provider(
            "sectigo-q",
            "http://timestamp.sectigo.com/qualified",
            True,
            (("sectigo-q", "f871f8976b4068d700d5f281084b4a29eaf4b8f35743330ba062fab46f58c2ed"),),
            # TL ES: "Qualified Certificates issued by Sectigo Qualified Time Stamping CA R35", QTST granted dal 2021-05-05
            ("cd0a3e00a1bdae3a159fa9e3d70f8e664f560fdce16357cbe440ed0b0d88244f",),
            "ES",
        ),
        Provider(
            "freetsa",
            "https://freetsa.org/tsr",
            False,
            (("freetsa", "a6379e7cecc05faa3cbf076013d745e327bbbaa38c0b9af22469d4701d18aabc"),),
        ),
        Provider(
            "digicert",
            "http://timestamp.digicert.com",
            False,
            (("digicert", "552f7bdcf1a7af9e6ce672017f4f12abf77240c78e761ac203d1d9d20ac89988"),),
        ),
    ]
}


# ETSI EN 319 422: qcStatement esi4-qtstStatement-1 = "questa è una marca temporale qualificata eIDAS"
QC_STATEMENTS_OID = "1.3.6.1.5.5.7.1.3"
QTST_STATEMENT_OID = "0.4.0.19422.1.1"


class StampError(Exception):
    """La TSA non ha restituito una marca valida per la richiesta fatta.

    permanent=True: ripetere la stessa richiesta darebbe lo stesso esito (es. policy rifiutata).
    """

    def __init__(self, message: str, permanent: bool = False):
        super().__init__(message)
        self.permanent = permanent


# fail_info per cui un nuovo tentativo può riuscire (RFC 3161 §2.4.2)
# Rifiuti definitivi (RFC 3161, PKIFailureInfo): ripetere la stessa richiesta darebbe lo stesso esito. Ogni altro
# rifiuto si ritenta: anche quelli senza motivo o con un motivo improprio, come "add_info_not_available" che FreeTSA
# usa per un suo errore interno ("Error during serial number generation", visto in produzione il 05/11/2026).
PERMANENT_FAILURES = {"bad_alg", "bad_request", "bad_data_format", "unaccepted_policy", "unaccepted_extension"}
TRANSIENT_FAILURES = {"time_not_available", "system_failure"}


class _TimeStampResp(core.Sequence):
    """TimeStampResp con token opzionale: nei rifiuti il token manca (asn1crypto lo vuole obbligatorio)."""

    _fields = [
        ("status", tsp.PKIStatusInfo),
        ("time_stamp_token", cms.ContentInfo, {"optional": True}),
    ]


@dataclass(frozen=True)
class StampInfo:
    gen_time: datetime
    accuracy_ms: int | None
    policy: str
    serial: int
    signer: str
    # qualifica verificata sul token: qtstStatement presente + certificato del servizio TL nel percorso
    qualified: bool
    # impronta dell'identità TL trovata nel percorso (solo per le qualificate)
    tl_identity: str | None = None
    # OCSP/CRL raccolte durante la verifica di una marca nuova: {"ocsp": [b64], "crl": [b64]}
    revinfo: dict | None = None
    # riverifica offline: OCSP/CRL archiviati presenti ma non utilizzabili
    revinfo_warning: str | None = None


@cache
def load_root(stem: str, sha256: str) -> x509.Certificate:
    _, _, der = pem.unarmor((ROOTS_DIR / f"{stem}.pem").read_bytes())
    cert = x509.Certificate.load(der)
    if cert.sha256.hex() != sha256:
        raise RuntimeError(f"root {stem} non corrisponde all'impronta fissata")
    return cert


def root_certs(provider: str) -> list[x509.Certificate]:
    return [load_root(stem, sha) for stem, sha in PROVIDERS[provider].roots]


def intermediate_certs(provider: str) -> list[x509.Certificate]:
    return [load_root(stem, sha) for stem, sha in PROVIDERS[provider].intermediates]


def build_request(digest: bytes) -> tuple[bytes, int]:
    """TimeStampReq DER per un'impronta SHA-256, con nonce casuale e richiesta dei certificati."""
    if len(digest) != 32:
        raise ValueError("serve un'impronta SHA-256 (32 byte)")
    nonce = secrets.randbits(63)
    req = tsp.TimeStampReq(
        {
            "version": "v1",
            "message_imprint": {
                "hash_algorithm": algos.DigestAlgorithm({"algorithm": "sha256"}),
                "hashed_message": digest,
            },
            "nonce": nonce,
            "cert_req": True,
        }
    )
    return req.dump(), nonce


def request_stamp(provider: Provider, request_der: bytes, client: httpx.Client) -> bytes:
    r = client.post(
        provider.url,
        content=request_der,
        headers={"Content-Type": "application/timestamp-query"},
        timeout=httpx.Timeout(30, connect=10),
        follow_redirects=True,
    )
    if r.status_code != 200:
        raise StampError(f"HTTP {r.status_code}")
    return r.content


def verify_response(
    provider: str,
    response_der: bytes,
    digest: bytes,
    nonce: int,
    *,
    now: datetime | None = None,
    max_skew_s: int = 120,
    revinfo: dict | None = None,
) -> StampInfo:
    """Verifica stato, impronta, nonce, firma e catena fino alla root fissata del provider.

    Con `now` (marca appena ottenuta): validazione all'ora corrente, genTime entro max_skew_s
    (più l'accuratezza dichiarata) dall'orologio del server, e raccolta di OCSP/CRL dalla rete.
    Senza `now` (riverifica di una marca archiviata): validazione al genTime, senza rete, usando
    l'eventuale `revinfo` conservato.
    """
    try:
        resp = _TimeStampResp.load(response_der)
        status_info = resp["status"]
        status = status_info["status"].native
    except Exception as e:
        raise StampError(f"risposta non RFC 3161: {e}") from e
    if status not in ("granted", "granted_with_mods"):
        fail = set(status_info["fail_info"].native or ())
        raise StampError(
            f"stato TSA: {status} {sorted(fail)} {status_info['status_string'].native or ''}".strip(),
            permanent=status == "rejection" and bool(fail & PERMANENT_FAILURES) and not fail & TRANSIENT_FAILURES,
        )
    try:
        signed_data = resp["time_stamp_token"]["content"]
        info = signed_data["encap_content_info"]["content"].parsed
        imprint = info["message_imprint"]
        imprint_alg = imprint["hash_algorithm"]["algorithm"].native
        imprint_value = imprint["hashed_message"].native
        token_nonce = info["nonce"].native
    except Exception as e:
        raise StampError(f"token di marca mancante o malformato: {e}") from e
    if imprint_alg != "sha256":
        raise StampError("algoritmo dell'impronta diverso da SHA-256")
    if imprint_value != digest:
        raise StampError("impronta diversa da quella richiesta")
    if token_nonce != nonce:
        raise StampError("nonce diverso da quello richiesto")

    gen_time = info["gen_time"].native
    acc = info["accuracy"]
    accuracy_ms = None
    if acc.native:
        accuracy_ms = (acc["seconds"].native or 0) * 1000 + (acc["millis"].native or 0)
    if now is not None:
        skew = abs((gen_time - now).total_seconds())
        if skew > max_skew_s + (accuracy_ms or 0) / 1000:
            raise StampError(f"genTime {gen_time.isoformat()} dista {skew:.0f} s dall'orologio del server")

    fetch = now is not None
    revinfo_warning = None
    try:
        result, collected = asyncio.run(
            _validate(signed_data, digest, provider, moment=now or gen_time, fetch=fetch, revinfo=revinfo)
        )
    except (ValueError, TypeError) as e:
        if fetch or not revinfo:
            raise
        # OCSP/CRL archiviati non riutilizzabili (es. OCSP di FreeTSA che asn1crypto legge ma non
        # ricodifica): si riverifica senza, segnalandolo
        result, collected = asyncio.run(
            _validate(signed_data, digest, provider, moment=gen_time, fetch=False, revinfo=None)
        )
        revinfo_warning = f"informazioni di revoca archiviate non utilizzabili: {e}"[:300]
    except (TimeoutError, aiohttp.ClientError, OSError) as e:
        if not fetch:
            raise
        # server OCSP/CRL irraggiungibile (pyHanko non lo tratta come soft-fail): si valida senza
        # rete e si annota il problema; la marca resta valida, manca solo la prova di non revoca
        result, collected = asyncio.run(
            _validate(signed_data, digest, provider, moment=now, fetch=False, revinfo=None)
        )
        collected = {"ocsp": [], "crl": [], "error": f"{type(e).__name__}: {e}"[:300]}
    if not (result.get("intact") and result.get("valid")):
        raise StampError("firma della marca non valida")
    if result.get("trust_problem_indic") is not None:
        raise StampError(f"catena non fidata: {result['trust_problem_indic']}")

    qualified = False
    tl_identity = None
    prov = PROVIDERS[provider]
    if prov.qualified:
        if not _has_qtst_statement(info):
            raise StampError("marca attesa qualificata ma senza esi4-qtstStatement-1")
        path = [c.sha256.hex() for c in result["validation_path"].iter_certs(include_root=True)]
        matches = [h for h in path if h in prov.tl_service_sha256]
        if not matches:
            raise StampError("nessun certificato del percorso corrisponde al servizio QTST della Trusted List")
        qualified, tl_identity = True, matches[-1]  # il più vicino al firmatario

    return StampInfo(
        gen_time=gen_time,
        accuracy_ms=accuracy_ms,
        policy=info["policy"].dotted,
        serial=info["serial_number"].native,
        signer=result["signing_cert"].subject.human_friendly,
        qualified=qualified,
        tl_identity=tl_identity,
        revinfo=collected,
        revinfo_warning=revinfo_warning,
    )


async def _validate(signed_data, digest: bytes, provider: str, *, moment, fetch: bool, revinfo: dict | None):
    def decode(key):
        return [base64.b64decode(x) for x in (revinfo or {}).get(key, [])]

    backend = AIOHttpFetcherBackend(per_request_timeout=15) if fetch else None
    try:
        ctx = ValidationContext(
            trust_roots=root_certs(provider),
            other_certs=intermediate_certs(provider),
            moment=moment,
            allow_fetching=fetch,
            fetcher_backend=backend,
            revocation_mode="soft-fail",
            ocsps=decode("ocsp"),
            crls=decode("crl"),
        )
        result = await validate_tst_signed_data(signed_data, ctx, expected_tst_imprint=lambda _alg: digest)
        collected = None
        if fetch:
            m = ctx.revinfo_manager
            collected = {
                "ocsp": [base64.b64encode(o.dump()).decode() for o in m.ocsps],
                "crl": [base64.b64encode(c.dump()).decode() for c in m.crls],
            }
        return result, collected
    finally:
        if backend is not None:
            await backend.close()


def _has_qtst_statement(info: tsp.TSTInfo) -> bool:
    if not info["extensions"].native:
        return False
    for ext in info["extensions"]:
        if ext["extn_id"].dotted != QC_STATEMENTS_OID:
            continue
        statements = QCStatements.load(ext["extn_value"].contents)
        if any(st["statement_id"].dotted == QTST_STATEMENT_OID for st in statements):
            return True
    return False


class _QCStatement(core.Sequence):
    _fields = [("statement_id", core.ObjectIdentifier), ("statement_info", core.Any, {"optional": True})]


class QCStatements(core.SequenceOf):
    _child_spec = _QCStatement

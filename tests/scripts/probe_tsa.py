"""M0 — sonda delle TSA: marca l'hash di un file di prova con ogni provider e riporta cosa torna.

Uso (dal PC, con Python e le dipendenze): python tests/scripts/probe_tsa.py [file]
Senza argomenti crea un file casuale (non si invia mai il contenuto, solo lo SHA-256).
Salva richieste (.tsq) e risposte (.tsr) in scratch/probe/ per la verifica con openssl:
  openssl ts -verify -queryfile accv.tsq -in accv.tsr -partial_chain -CAfile <certs>

Nota: le TSA reali (ACCV, Sectigo, DigiCert) mettono i certificati del SignedData in ordine
non canonico DER; rfc3161-client li rifiuta, asn1crypto (usato da pyHanko) no.
"""

import asyncio
import hashlib
import os
import secrets
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
from asn1crypto import algos, tsp
from pyhanko.sign.validation.generic_cms import validate_tst_signed_data

PROVIDERS = [
    # nome, url, qualificata eIDAS (dichiarata)
    ("accv", "http://tss.accv.es:8318/tsa", True),
    ("sectigo-q", "http://timestamp.sectigo.com/qualified", True),
    ("freetsa", "https://freetsa.org/tsr", False),
    ("digicert", "http://timestamp.digicert.com", False),
]

OUT = Path(__file__).resolve().parents[2] / "scratch" / "probe"


def build_request(digest: bytes) -> tuple[bytes, int]:
    nonce = secrets.randbits(63)
    req = tsp.TimeStampReq(
        {
            "version": "v1",
            "message_imprint": tsp.MessageImprint(
                {
                    "hash_algorithm": algos.DigestAlgorithm({"algorithm": "sha256"}),
                    "hashed_message": digest,
                }
            ),
            "nonce": nonce,
            "cert_req": True,
        }
    )
    return req.dump(), nonce


def probe(name: str, url: str, qualified: bool, digest: bytes) -> None:
    print(f"\n=== {name} ({'qualificata' if qualified else 'semplice'}) {url}")
    body, nonce = build_request(digest)
    (OUT / f"{name}.tsq").write_bytes(body)
    sent = datetime.now(timezone.utc)
    t0 = time.monotonic()
    try:
        r = httpx.post(
            url,
            content=body,
            headers={"Content-Type": "application/timestamp-query"},
            timeout=httpx.Timeout(20, connect=10),
            follow_redirects=True,
        )
    except httpx.HTTPError as e:
        print(f"  ERRORE rete: {e!r}")
        return
    ms = (time.monotonic() - t0) * 1000
    print(f"  HTTP {r.status_code}  {r.headers.get('content-type')}  {len(r.content)} B  {ms:.0f} ms")
    if r.status_code != 200:
        print(f"  corpo: {r.content[:300]!r}")
        return
    (OUT / f"{name}.tsr").write_bytes(r.content)

    resp = tsp.TimeStampResp.load(r.content)
    status = resp["status"]["status"].native
    print(f"  status:   {status}")
    if status not in ("granted", "granted_with_mods"):
        return
    token = resp["time_stamp_token"]
    signed_data = token["content"]
    info = signed_data["encap_content_info"]["content"].parsed
    acc = info["accuracy"].native if info["accuracy"].native else {}
    imprint = info["message_imprint"]
    print(f"  genTime:  {info['gen_time'].native.isoformat()}   (inviata {sent.isoformat(timespec='milliseconds')})")
    print(f"  accuracy: {acc}")
    print(f"  policy:   {info['policy'].dotted}")
    print(f"  serial:   {info['serial_number'].native}")
    print(f"  nonce ok: {info['nonce'].native == nonce}")
    print(
        f"  imprint:  {imprint['hash_algorithm']['algorithm'].native} "
        f"ok={imprint['hashed_message'].native == digest}"
    )
    for cert in signed_data["certificates"]:
        c = cert.chosen
        print(f"  cert: {c.subject.human_friendly}")
        print(f"        issuer: {c.issuer.human_friendly} | fino al {c['tbs_certificate']['validity']['not_after'].native.date()}")

    # Integrità crittografica della firma (senza ancora la fiducia nella catena: arriverà in M1).
    status = asyncio.run(
        validate_tst_signed_data(signed_data, None, expected_tst_imprint=lambda _alg: digest)
    )
    print(f"  firma:    intact={status['intact']} valid={status['valid']}")


def main() -> None:
    sys.stdout.reconfigure(line_buffering=True)
    OUT.mkdir(parents=True, exist_ok=True)
    data = Path(sys.argv[1]).read_bytes() if len(sys.argv) > 1 else os.urandom(1024)
    (OUT / "data.bin").write_bytes(data)
    digest = hashlib.sha256(data).digest()
    print(f"sha256: {digest.hex()}")
    for i, (name, url, q) in enumerate(PROVIDERS):
        if i:
            time.sleep(2)
        probe(name, url, q, digest)


if __name__ == "__main__":
    main()

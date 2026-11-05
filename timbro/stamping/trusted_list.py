"""Controllo dei provider qualificati sulla EU Trusted List (LOTL → TL nazionale).

Per ogni provider qualificato cerca il servizio QTST la cui identità corrente coincide con i
certificati fissati in tsa.PROVIDERS e ne riporta lo stato (deve essere "granted").

Limite noto: la firma XML delle liste non viene verificata; le liste si scaricano in HTTPS dai
domini ufficiali (ec.europa.eu e quelli indicati dalla LOTL).
"""

import base64
import hashlib
import os
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import httpx

from timbro.stamping.tsa import PROVIDERS

LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"
NS = {"tsl": "http://uri.etsi.org/02231/v2#"}
QTST = "http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST"
GRANTED = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted"
TSL_MIME = "application/vnd.etsi.tsl+xml"


@dataclass(frozen=True)
class TlCheck:
    provider: str
    identity: str  # impronta SHA-256 del certificato di servizio fissato
    territory: str
    found: bool
    granted: bool
    service_name: str | None = None
    status: str | None = None
    status_since: str | None = None
    tl_sequence: str | None = None
    tl_issued: str | None = None


def national_tl_url(lotl_xml: bytes, territory: str) -> str:
    root = ET.fromstring(lotl_xml)
    for ptr in root.iterfind(".//tsl:OtherTSLPointer", NS):
        terr = ptr.findtext(".//tsl:SchemeTerritory", namespaces=NS)
        mime = [e.text for e in ptr.iter() if e.tag.endswith("MimeType")]
        if terr == territory and TSL_MIME in mime:
            return ptr.findtext("tsl:TSLLocation", namespaces=NS).strip()
    raise LookupError(f"nessuna TL XML per {territory} nella LOTL")


def _current_certs(service: ET.Element) -> set[str]:
    info = service.find("tsl:ServiceInformation", NS)
    out = set()
    for c in info.iterfind("tsl:ServiceDigitalIdentity/tsl:DigitalId/tsl:X509Certificate", NS):
        if c.text and c.text.strip():
            out.add(hashlib.sha256(base64.b64decode(c.text)).hexdigest())
    return out


def check_provider(tl_xml: bytes, provider: str) -> list[TlCheck]:
    """Un risultato per ogni identità di servizio fissata nel provider (non trovata = found False).

    Se la stessa identità compare in più servizi (es. registrata di nuovo dopo un ritiro) vale la
    voce granted, poi quella con lo stato più recente.
    """
    p = PROVIDERS[provider]
    root = ET.fromstring(tl_xml)
    seq = root.findtext(".//tsl:TSLSequenceNumber", namespaces=NS)
    issued = root.findtext(".//tsl:ListIssueDateTime", namespaces=NS)
    found: dict[str, TlCheck] = {}
    for svc in root.iterfind(".//tsl:TSPService", NS):
        info = svc.find("tsl:ServiceInformation", NS)
        if info.findtext("tsl:ServiceTypeIdentifier", namespaces=NS) != QTST:
            continue
        status = info.findtext("tsl:ServiceStatus", namespaces=NS)
        for h in _current_certs(svc) & set(p.tl_service_sha256):
            candidate = TlCheck(
                provider=provider,
                identity=h,
                territory=p.tl_territory,
                found=True,
                granted=status == GRANTED,
                service_name=info.findtext("tsl:ServiceName/tsl:Name", namespaces=NS),
                status=status.rsplit("/", 1)[-1] if status else None,
                status_since=info.findtext("tsl:StatusStartingTime", namespaces=NS),
                tl_sequence=seq,
                tl_issued=issued,
            )
            current = found.get(h)
            if current is None or (candidate.granted, candidate.status_since or "") > (
                current.granted,
                current.status_since or "",
            ):
                found[h] = candidate
    return [
        found.get(h) or TlCheck(provider, h, p.tl_territory, False, False, tl_sequence=seq, tl_issued=issued)
        for h in p.tl_service_sha256
    ]


def check_all(client: httpx.Client, archive_dir: Path | None = None) -> list[TlCheck]:
    """Controlla i provider qualificati. Con archive_dir salva LOTL e TL nazionali scaricate
    (nome con data, territorio e numero di sequenza): la copia firmata della lista dimostra in
    futuro lo stato "granted" all'epoca delle marche."""
    lotl = client.get(LOTL_URL, timeout=60).raise_for_status().content
    cache: dict[str, bytes] = {}
    out = []
    for name, p in PROVIDERS.items():
        if not p.qualified:
            continue
        if p.tl_territory not in cache:
            url = national_tl_url(lotl, p.tl_territory)
            cache[p.tl_territory] = client.get(url, timeout=120).raise_for_status().content
        out.extend(check_provider(cache[p.tl_territory], name))
    if archive_dir is not None:
        _archive(archive_dir, "EU-LOTL", lotl)
        for territory, xml in cache.items():
            _archive(archive_dir, territory, xml)
    return out


def _archive(archive_dir: Path, territory: str, xml: bytes) -> Path:
    """Una copia per numero di sequenza: il controllo settimanale non duplica una lista che non è cambiata.

    Scrittura atomica (file temporaneo, fsync, rename): un'interruzione non lascia mai un XML troncato, e un file
    esistente con contenuto diverso (es. troncato da una versione vecchia) viene sostituito.
    """
    seq = ET.fromstring(xml).findtext(".//tsl:TSLSequenceNumber", namespaces=NS) or "x"
    archive_dir.mkdir(parents=True, exist_ok=True)
    existing = sorted(archive_dir.glob(f"*_{territory}_{seq}.xml"))
    if existing and existing[-1].read_bytes() == xml:
        return existing[-1]
    path = archive_dir / f"{datetime.now(UTC).date().isoformat()}_{territory}_{seq}.xml"
    tmp = path.with_suffix(".xml.part")
    try:
        with open(tmp, "wb") as f:
            f.write(xml)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
    return path

"""Verifica completa dell'archivio (offline): catena, marche, coerenza fra marche e record.

Rileva fra l'altro la cancellazione degli ultimi record: ogni marca giornaliera fissa la testa
della catena di quel giorno, che deve esistere ed essere l'ultimo record ricevuto entro la
mezzanotte (ora italiana).
"""

import hashlib
import json
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from asn1crypto import tsp
from sqlalchemy import select
from sqlalchemy.orm import Session

from timbro import chain
from timbro.manifest import public_key_der
from timbro.models import Device, Punch, Stamp
from timbro.stamping import tsa

LOCAL_TZ = ZoneInfo("Europe/Rome")


@dataclass
class Report:
    links: int = 0
    stamps_checked: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    missing_videos: list[int] = field(default_factory=list)  # video conservati per il database ma assenti sul disco

    @property
    def ok(self) -> bool:
        return not self.errors and not self.missing_videos

    def missing_video_errors(self, still_kept: set[int] | None = None) -> list[str]:
        """Errori per i video mancanti; still_kept = quelli che risultano ancora conservati fuori dalla fotografia."""
        ids = [i for i in self.missing_videos if still_kept is None or i in still_kept]
        return [f"timbratura {i}: video conservato ma file mancante" for i in ids]


def verify_archive(s: Session, today: date | None = None, videos_dir: Path | None = None) -> Report:
    """Da eseguire in una transazione REPEATABLE READ (checks.run_archive): tutte le letture vedono lo stesso
    istante, anche se nel frattempo arrivano timbrature nuove. Le marche si leggono a blocchi: la memoria non
    cresce con l'archivio. Con videos_dir controlla anche che ogni video conservato esista e abbia l'impronta
    del manifest."""
    r = Report()
    punches = s.scalars(select(Punch).order_by(Punch.id)).all()
    keys = {d.id: public_key_der(d.public_key_pem) for d in s.scalars(select(Device))}

    # 1. catena
    try:
        r.links = chain.verify(
            chain.Link(p.prev_chain_hash, p.chain_hash, p.manifest_raw, p.device_signature, keys[p.device_id], p.server_received_at)
            for p in punches
        )
    except ValueError as e:
        r.errors.append(f"catena: {e}")

    # 2. marche delle timbrature: impronta giusta, almeno una qualificata sul video
    by_id = {p.id: p for p in punches}
    qualified_ok = set(
        s.scalars(
            select(Stamp.punch_id).where(
                Stamp.target == "video", Stamp.qualified.is_(True), Stamp.status == "complete"
            )
        )
    )
    for p in punches:
        if p.id not in qualified_ok:
            r.warnings.append(f"timbratura {p.id}: nessuna marca qualificata completata sul video")
    for st_id, punch_id, target, provider, digest in s.execute(
        select(Stamp.id, Stamp.punch_id, Stamp.target, Stamp.provider, Stamp.digest).where(Stamp.punch_id.is_not(None))
    ):
        p = by_id.get(punch_id)
        expected = {
            "video": p.sha256,
            "record": p.chain_hash,
            "attest": hashlib.sha256(p.attestation_pem.encode()).hexdigest() if p.attestation_pem else None,
        }.get(target) if p else None
        if digest != expected:
            r.errors.append(f"marca {st_id} ({target}/{provider}): impronta diversa dal record {punch_id}")

    # 2b. video conservati: il file c'è ed è quello del manifest (dimensione e SHA-256)
    if videos_dir is not None:
        for p in punches:
            if p.video_received_at is None or p.video_deleted_at is not None or not p.video_path:
                continue
            path = videos_dir / p.video_path
            if not path.is_file():
                # può essere stato cancellato (conservazione, a mano) dopo l'istante fotografato dalla verifica:
                # checks.run_archive lo ricontrolla fuori dalla fotografia prima di segnalarlo
                r.missing_videos.append(p.id)
            elif path.stat().st_size != p.size or _sha256_file(path) != p.sha256:
                r.errors.append(f"timbratura {p.id}: il file video non corrisponde all'impronta del manifest")

    # 3. marche giornaliere della catena: devono fissare l'ultimo record di quel giorno
    today = today or datetime.now(LOCAL_TZ).date()
    heads = _daily_heads(punches, until=today)
    stamped_days = set()
    for chain_day, provider, digest in s.execute(
        select(Stamp.chain_day, Stamp.provider, Stamp.digest).where(Stamp.target == "chain")
    ):
        stamped_days.add(chain_day)
        head = heads.get(chain_day)
        if head is None or head != digest:
            r.errors.append(
                f"marca giornaliera {chain_day} ({provider}): la testa marcata non è l'ultimo record del giorno"
                " (record cancellati o alterati?)"
            )
    for day in heads:
        if day < today and day not in stamped_days:
            r.warnings.append(f"giorno {day}: nessuna marca giornaliera della catena")

    # 4. riverifica offline dei token RFC 3161 completati, a blocchi (token e risposte OCSP pesano). Si leggono le
    # colonne, non oggetti Stamp: le righe non entrano nella sessione e la memoria resta quella di un blocco, senza
    # svuotare la sessione a metà lettura (expunge_all durante yield_per rompe il blocco successivo)
    rows = s.execute(
        select(
            Stamp.id, Stamp.provider, Stamp.request, Stamp.token, Stamp.digest, Stamp.revinfo_json, Stamp.gen_time,
            Stamp.qualified,
        )
        .where(Stamp.kind == "rfc3161", Stamp.status == "complete")
        .order_by(Stamp.id)
        .execution_options(yield_per=100)
    )
    for st in rows:
        r.stamps_checked += 1
        try:
            nonce = tsp.TimeStampReq.load(st.request)["nonce"].native
            revinfo = json.loads(st.revinfo_json) if st.revinfo_json else None
            info = tsa.verify_response(st.provider, st.token, bytes.fromhex(st.digest), nonce, revinfo=revinfo)
            if info.gen_time != st.gen_time:
                r.errors.append(f"marca {st.id}: genTime del token diverso da quello registrato")
            if info.revinfo_warning:
                r.warnings.append(f"marca {st.id} ({st.provider}): {info.revinfo_warning}")
            if st.qualified and not info.qualified:
                r.errors.append(f"marca {st.id}: registrata come qualificata ma il token non lo dimostra")
        except Exception as e:
            r.errors.append(f"marca {st.id} ({st.provider}): {e}")
    return r


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _daily_heads(punches: list[Punch], until: date) -> dict[date, str]:
    """Per ogni giorno dalla prima timbratura: chain_hash dell'ultimo record ricevuto entro mezzanotte."""
    if not punches:
        return {}
    heads: dict[date, str] = {}
    day = punches[0].server_received_at.astimezone(LOCAL_TZ).date()
    i = 0
    head = None
    while day <= until:
        end = datetime.combine(day + timedelta(days=1), time.min, LOCAL_TZ)
        while i < len(punches) and punches[i].server_received_at < end:
            head = punches[i].chain_hash
            i += 1
        heads[day] = head
        day += timedelta(days=1)
    return heads

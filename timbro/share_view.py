"""Dati della pagina pubblica di un link (/s/<token>): giornate, timbrature, firme, marche, attestazione, posizione.

Mai note private, giustificativi o token: solo ciò che serve a chi riceve il link per capire e verificare. La nota
della contestazione sì, perché l'ha scritta l'utente proprio per l'ente.
"""

import hashlib
import json
from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from timbro import mailer, orario, services, versione
from timbro.manifest import public_key_der
from timbro.models import ContestNotice, Device, Punch, ShareLink, Stamp

LOCAL_TZ = services.LOCAL_TZ
BAR_START, BAR_END = 7 * 60, 19 * 60  # barra della giornata: dalle 7 alle 19

PROVIDER_NAMES = {
    "accv": "ACCV · Generalitat Valenciana",
    "sectigo-q": "Sectigo Qualified Time Stamping",
    "freetsa": "FreeTSA",
    "digicert": "DigiCert",
    "ots": "OpenTimestamps · Bitcoin",
}
TARGETS = {
    "video": "sul video",
    "record": "sul record della timbratura",
    "attest": "sull'attestazione del telefono",
    "chain": "sulla catena del giorno",
}
LEVELS = {"tee": "TEE (area sicura del processore)", "strongbox": "StrongBox (chip sicuro dedicato)"}


def _local(dt: datetime | None) -> datetime | None:
    return dt.astimezone(LOCAL_TZ) if dt else None


def _minutes(dt: datetime) -> int:
    return dt.hour * 60 + dt.minute


def _stamp(st: Stamp) -> dict:
    info = json.loads(st.info_json) if st.info_json else {}
    if st.kind == "ots":
        state = "confermata su Bitcoin" if st.status == "complete" else "in attesa di conferma su Bitcoin"
        if st.status == "complete" and info.get("block_height"):
            state += f" (blocco {info['block_height']})"
    else:
        state = "valida" if st.status == "complete" else "in corso"
    return {
        "provider": PROVIDER_NAMES.get(st.provider, st.provider),
        "provider_id": st.provider,
        "qualified": st.qualified,
        "ots": st.kind == "ots",
        "target": TARGETS.get(st.target, st.target),
        "time": _local(st.gen_time),
        "state": state,
        "complete": st.status == "complete",
    }


PROVIDER_ORDER = ["accv", "sectigo-q"]  # le qualificate in testa, poi le altre RFC 3161, Bitcoin in fondo


def _stamp_order(st: Stamp):
    """Raggruppate per prestatore (ACCV, Sectigo, ..., OpenTimestamps) e, dentro ciascuno, video, record, attestazione."""
    return (st.kind == "ots",
            PROVIDER_ORDER.index(st.provider) if st.provider in PROVIDER_ORDER else len(PROVIDER_ORDER), st.provider,
            ["video", "record", "attest", "chain"].index(st.target) if st.target in TARGETS else 9, st.id)


def _visible(st: Stamp) -> bool:
    return st.status == "complete" or (st.kind == "ots" and st.status in ("pending", "upgrading"))


def _attestation(p: Punch) -> dict | None:
    if p.attestation_json is None:
        return None
    r = json.loads(p.attestation_json)
    return {
        "ok": bool(r.get("ok")),
        "level": LEVELS.get(r.get("security_level"), r.get("security_level") or "?"),
        "locked": r.get("device_locked"),
        "boot": r.get("verified_boot_state"),
        # nome e versione (versionCode) dell'app che ha chiesto l'attestazione, come li dichiara Android
        "apps": ", ".join(f"{p[0]} versione {p[1]}" for p in r.get("packages", [])) or "?",
        # chi ha emesso la chiave di attestazione: Google (RKP, certificati brevi) o la fabbrica (chiavi di alcuni
        # modelli trapelate: un telefono modificato con quelle può fingersi integro finché Google non le revoca)
        "provisioning": r.get("provisioning"),
        "problems": r.get("problems", []),
    }


def _location(p: Punch) -> dict | None:
    if not p.location_json:
        return None
    loc = json.loads(p.location_json)
    lat, lon = loc.get("lat"), loc.get("lon")
    if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
        return None
    return {
        "lat": f"{lat:.6f}",
        "lon": f"{lon:.6f}",
        "accuracy": loc.get("accuracy_m"),
        "mock": loc.get("is_mock"),
        "osm": f"https://www.openstreetmap.org/?mlat={lat:.6f}&mlon={lon:.6f}#map=19/{lat:.6f}/{lon:.6f}",
    }


def _code(p: Punch, manifest: dict, sources: str) -> dict:
    """Versioni del codice di questa timbratura, con le release pubbliche (docs/PROGETTO.md §25)."""
    app_version = manifest.get("app_version")
    app_commit, local_build = versione.app_source(app_version)
    return {
        "app": (app_version or "?").split("+", 1)[0],
        "app_commit": app_commit[:7] if app_commit else None,
        "app_release": versione.release_url(sources, app_commit) if sources and app_commit and not local_build else None,
        "app_local": local_build,
        "server": p.server_version[:7] if p.server_version else None,
        "server_release": versione.release_url(sources, p.server_version) if sources else None,
    }


def _punch(s: Session, p: Punch, keys: dict, allow_evidence: bool, base: str, sources: str = "") -> dict:
    tc = services.time_check(p)
    t = _local(tc.time)
    declared = services._parse_device_time(p.device_time)
    device = keys.get(p.device_id)
    if device is None:
        d = s.get(Device, p.device_id)
        device = keys[p.device_id] = {"name": d.name, "fp": hashlib.sha256(public_key_der(d.public_key_pem)).hexdigest()}
    manifest = json.loads(p.manifest_raw)
    received = _local(p.server_received_at)
    return {
        "id": p.id,
        "kind": p.kind,
        "label": "Entrata" if p.kind == "in" else "Uscita",
        "time": t,
        "contested": p.contested,
        "video_url": f"{base}/p/{p.id}/video" if p.video_path and not p.video_deleted_at else None,
        "video_deleted": p.video_deleted_at is not None,
        "sha256": p.sha256,
        "size_mb": f"{p.size / 1_000_000:.1f}".replace(".", ","),
        "duration_s": round((p.duration_ms or 0) / 1000),
        "device_time": p.device_time,
        "device_clock": _local(declared) if declared else None,
        "time_confirmed": tc.confirmed_by,
        "time_warning": tc.warning,
        "received": received,
        # ritardo della ricezione sull'ora dichiarata dal telefono (coda offline, o orologio spostato)
        "delay_s": round((p.server_received_at - declared).total_seconds()) if declared else None,
        "device": device,
        "chain_hash": p.chain_hash,
        "attestation": _attestation(p),
        "location": _location(p),
        "wifi": services.wifi_summary(s, p),
        "app_version": manifest.get("app_version"),
        "code": _code(p, manifest, sources),
        "stamps": [_stamp(st) for st in sorted(p.stamps, key=_stamp_order) if _visible(st)],
        "evidence_url": f"{base}/p/{p.id}/evidence.zip" if allow_evidence else None,
    }


def _bar(punches: list[dict]) -> list[dict]:
    """Tratti lavorati (entrata -> uscita successiva) in percentuale della barra 7-19."""
    out, start = [], None
    span = BAR_END - BAR_START

    def pct(m: int) -> float:
        return round((min(max(m, BAR_START), BAR_END) - BAR_START) / span * 100, 2)

    for p in punches:
        m = _minutes(p["time"])
        if p["kind"] == "in":
            start = m
        elif start is not None:
            out.append({"left": pct(start), "width": max(pct(m) - pct(start), 0.5)})
            start = None
    return out


def _pauses(punches: list[dict]) -> list[dict]:
    out = []
    for a, b in zip(punches, punches[1:]):
        if a["kind"] == "out" and b["kind"] == "in":
            out.append({"after": a["id"], "minutes": round((b["time"] - a["time"]).total_seconds() / 60)})
    return out


def _hours(result, schedule) -> dict | None:
    """Ore valide del giorno secondo l'orario dell'utente, con le correzioni spiegate (scheda della pagina)."""
    if schedule is None:
        return None
    return {
        "worked": orario.duration(result.worked_min),
        "due": orario.duration(result.due_min or 0),
        "balance": orario.balance(result.balance_min) if result.balance_min is not None else None,
        "negative": (result.balance_min or 0) < 0,
        "adjustments": [orario.describe(n) for n in result.adjustments],
        "anomalies": [orario.describe(n) for n in result.anomalies],
        "pause": result.pause_min,
        "pause_counted": result.pause_counted_min,
        "schedule": orario.describe_schedule(schedule),
    }


def build(s: Session, sh: ShareLink, ids: list[int], base: str, sources: str = "") -> dict:
    punches = s.scalars(select(Punch).where(Punch.id.in_(ids)).order_by(Punch.id)).all()
    keys: dict = {}
    views = [_punch(s, p, keys, sh.allow_evidence_download, base, sources) for p in punches]
    by_day: dict[date, list[dict]] = {}
    for v in sorted(views, key=lambda v: (v["time"], v["id"])):
        by_day.setdefault(v["time"].date(), []).append(v)
    orm = {p.id: p for p in punches}
    days = []
    for d, items in sorted(by_day.items()):
        chain = s.scalars(select(Stamp).where(Stamp.target == "chain", Stamp.chain_day == d)).all()
        result, schedule = services.day_hours(s, sh.user_id, d, [orm[v["id"]] for v in items])
        days.append({
            "hours": _hours(result, schedule),
            "date": d,
            "label": mailer.italian_day(d),
            "punches": items,
            "bar": _bar(items),
            "pauses": {x["after"]: x["minutes"] for x in _pauses(items)},
            "chain_stamps": [_stamp(st) for st in sorted(chain, key=_stamp_order) if _visible(st)],
        })
    notices = s.scalars(select(ContestNotice).where(ContestNotice.share_id == sh.id).order_by(ContestNotice.id)).all()
    contested = [v for v in views if v["contested"]]
    return {
        "sources": sources,
        "owner": services.get_profile(s, sh.user_id),
        "days": days,
        "contest": {
            "day": mailer.italian_day(sh.contest_day) if sh.contest_day else None,
            "punches": contested,
            "note": sh.note,
            "sent": _local(next((n.sent_at for n in reversed(notices) if n.sent_at), None)),
        } if sh.contest_day or contested or notices else None,
        "expires": _local(sh.expires_at),
    }

"""Problemi in corso, da segnalare su Telegram.

Li espone GET /internal/problemi (non pubblicato da nginx). Sul server timbro-controllo lo legge ogni 10 minuti e
manda un avviso quando una chiave compare (⚠️) e quando sparisce (✅).

Le chiavi dei problemi legati alle timbrature contengono il giorno (ora italiana) delle timbrature coinvolte, es.
"video-mancanti:2026-11-05": un caso vecchio che non si risolve mai resta aperto senza nascondere quelli nuovi,
che hanno un'altra chiave e quindi un altro avviso. Dentro lo stesso giorno il testo può cambiare (conteggi) senza
ripetere l'avviso. Nessun limite di età: un giorno resta aperto finché il problema c'è. Mai contenuti delle
timbrature nei testi, solo numeri, ore e provider.
"""

import json
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import and_, exists, select
from sqlalchemy.orm import Session, aliased

from timbro import checks, services
from timbro.config import Settings
from timbro.models import ContestNotice, Device, Punch, Stamp
from timbro.stamping import tsa

LOCAL_TZ = ZoneInfo("Europe/Rome")


@dataclass(frozen=True)
class Problem:
    key: str
    text: str


@dataclass
class AppState:
    """Stato del processo che serve ai controlli (dal worker e dall'avvio dell'app)."""

    worker_enabled: bool = True
    worker_last_loop: datetime | None = None
    worker_failing: dict[str, tuple[datetime, str]] | None = None
    instance_lock_ok: bool = True
    schema_state: str = "aggiornato"


def _ids(ids: list[int], limit: int = 10) -> str:
    shown = ", ".join(f"#{i}" for i in ids[:limit])
    return shown + (f" e altre {len(ids) - limit}" if len(ids) > limit else "")


def _local(dt: datetime) -> str:
    return dt.astimezone(LOCAL_TZ).strftime("%d/%m %H:%M")


def _day(dt: datetime) -> date:
    return dt.astimezone(LOCAL_TZ).date()


def _by_day(rows) -> dict[date, list[int]]:
    """(id, ricevuta) -> {giorno italiano: [id, ...]}"""
    out: dict[date, list[int]] = defaultdict(list)
    for pid, received in rows:
        out[_day(received)].append(pid)
    return dict(sorted(out.items()))


def find(s: Session, settings: Settings, now: datetime, state: AppState | None = None) -> list[Problem]:
    state = state or AppState()
    out: list[Problem] = []
    out += _tsa_providers(s, settings)
    out += _punches_without_stamp(s, settings, now)
    out += _chain(s, settings, now)
    out += _missing_videos(s, settings, now)
    out += _ots(s, settings, now)
    out += _attestations(s, settings)
    out += _device_clock(s, now)
    out += _mails(s, settings, now)
    out += _checks(s, settings, now)
    out += _new_devices(s, now)
    out += _process(settings, now, state)
    return out


def _process(settings: Settings, now: datetime, state: AppState) -> list[Problem]:
    out = []
    if state.schema_state == "piu-nuovo":
        out.append(Problem("schema", "lo schema del database è più nuovo del codice in funzione (ritorno a una versione "
                                     "precedente dopo una migrazione?): l'app gira, ma va installato un commit nuovo"))
    if not state.instance_lock_ok:
        out.append(Problem("lock", "l'app ha perso il lock d'istanza sul database e non riesce a riprenderlo: "
                                   "c'è un altro processo Timbro sullo stesso database?"))
    if state.worker_enabled:
        stale = timedelta(minutes=settings.alert_worker_stale_min)
        if state.worker_last_loop is None or now - state.worker_last_loop > stale:
            out.append(Problem("worker", "il worker (marche, conservazione, controlli) non fa un giro completo da più di "
                                         f"{settings.alert_worker_stale_min} minuti: vedi il log"))
        for name, (since, error) in sorted((state.worker_failing or {}).items()):
            if now - since > stale:
                out.append(Problem(f"worker-{name}", f"la fase «{name}» del worker fallisce dalle {_local(since)}: {error}"))
    return out


def _tsa_providers(s: Session, settings: Settings) -> list[Problem]:
    """Un provider RFC 3161 la cui marca più recente già tentata è fallita o non riesce da N tentativi."""
    out = []
    for name in dict.fromkeys(settings.tsa_providers + settings.record_tsa_providers):
        st = s.execute(
            select(Stamp.status, Stamp.attempts, Stamp.last_error)
            .where(Stamp.provider == name, Stamp.kind == "rfc3161", (Stamp.attempts > 0) | (Stamp.status != "pending"))
            .order_by(Stamp.id.desc())
            .limit(1)
        ).first()
        if st is None or st.status == "complete":
            continue
        if st.status == "failed" or st.attempts >= settings.alert_tsa_failed_attempts:
            label = "qualificata " if tsa.PROVIDERS[name].qualified else ""
            out.append(Problem(f"tsa:{name}", f"la TSA {label}{name} non riesce a marcare ({st.attempts} tentativi, "
                                              f"{st.status}): {(st.last_error or '?')[:200]}"))
    return out


def _punches_without_stamp(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    """Timbrature più vecchie della soglia senza una marca qualificata completa sul video, o senza marca sul record."""
    old = now - timedelta(minutes=settings.alert_stamp_after_min)
    st = aliased(Stamp)
    checks_ = [
        ("marca-qualificata", "senza marca qualificata",
         and_(st.target == "video", st.qualified.is_(True), st.status == "complete")),
    ]
    if settings.record_tsa_providers:
        checks_.append(("marca-record", "senza marca sul record",
                        and_(st.target == "record", st.kind == "rfc3161", st.status == "complete")))
    out = []
    for key, what, cond in checks_:
        rows = s.execute(
            select(Punch.id, Punch.server_received_at)
            .where(Punch.server_received_at < old, ~exists().where(and_(st.punch_id == Punch.id, cond)))
            .order_by(Punch.id)
        ).all()
        for day, ids in _by_day(rows).items():
            out.append(Problem(f"{key}:{day.isoformat()}", f"{len(ids)} timbrature del {day:%d/%m} {what} dopo "
                                                          f"{settings.alert_stamp_after_min} minuti: {_ids(ids)}"))
    return out


def _chain(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    """Giorni con timbrature la cui testa di catena non ha una marca qualificata completa.

    La marca del giorno D deve esserci entro alert_chain_local_hour del giorno D+1. Si guardano solo i giorni in cui
    sono arrivate timbrature (lì la catena è cambiata), senza limite di età: un giorno mancato resta aperto.
    """
    local = now.astimezone(LOCAL_TZ)
    last_day = local.date() - timedelta(days=1 if local.hour >= settings.alert_chain_local_hour else 2)
    days = {_day(r) for r in s.scalars(select(Punch.server_received_at))}
    stamped = set(
        s.scalars(select(Stamp.chain_day).where(Stamp.target == "chain", Stamp.qualified.is_(True), Stamp.status == "complete"))
    )
    out = []
    for day in sorted(d for d in days if d <= last_day and d not in stamped):
        out.append(Problem(f"catena:{day.isoformat()}",
                           f"manca la marca qualificata giornaliera della catena per il {day:%d/%m/%Y}"))
    return out


def _missing_videos(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    old = now - timedelta(hours=settings.alert_video_missing_after_h)
    rows = s.execute(
        select(Punch.id, Punch.server_received_at)
        .where(Punch.server_received_at < old, Punch.video_received_at.is_(None), Punch.video_deleted_at.is_(None))
        .order_by(Punch.id)
    ).all()
    return [
        Problem(f"video-mancanti:{day.isoformat()}",
                f"{len(ids)} timbrature del {day:%d/%m} senza video dopo {settings.alert_video_missing_after_h} ore "
                f"(il telefono non l'ha caricato?): {_ids(ids)}")
        for day, ids in _by_day(rows).items()
    ]


def _ots(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    old = now - timedelta(hours=settings.alert_ots_pending_after_h)
    rows = s.execute(
        select(Stamp.id, Stamp.created_at).where(
            Stamp.kind == "ots", Stamp.status.in_(("pending", "upgrading")), Stamp.created_at < old
        )
    ).all()
    return [
        Problem(f"ots:{day.isoformat()}", f"{len(ids)} prove OpenTimestamps del {day:%d/%m} non ancora confermate su "
                                          f"Bitcoin dopo {settings.alert_ots_pending_after_h} ore")
        for day, ids in _by_day(rows).items()
    ]


def _attestations(s: Session, settings: Settings) -> list[Problem]:
    """Timbrature senza attestazione hardware valida (mancante, telefono con root, app diversa, ...). Avviso subito:
    va capito finché si è ancora in tempo a rifare la prova."""
    if not settings.attestation_required:
        return []
    by_day: dict[date, list[tuple[int, str]]] = defaultdict(list)
    for pid, received, raw in s.execute(
        select(Punch.id, Punch.server_received_at, Punch.attestation_json).order_by(Punch.id)
    ):
        if raw is None:
            by_day[_day(received)].append((pid, "attestazione mancante"))
            continue
        r = json.loads(raw)
        if not r.get("ok"):
            by_day[_day(received)].append((pid, "; ".join(r.get("problems", [])) or "?"))
    return [
        Problem(f"attestazione:{day.isoformat()}",
                f"{len(rows)} timbrature del {day:%d/%m} senza attestazione hardware valida: {_ids([i for i, _ in rows])} "
                f"(#{rows[0][0]}: {rows[0][1][:200]})")
        for day, rows in sorted(by_day.items())
    ]


def _device_clock(s: Session, now: datetime, days: int = 31) -> list[Problem]:
    """Timbrature (ultimi `days` giorni) con l'ora del telefono smentita da un orologio che l'utente non può spostare
    o dalla ricezione: orologio cambiato a mano prima della ripresa, o sbagliato. Si usa l'altra ora e si avvisa."""
    from types import SimpleNamespace

    by_day: dict[date, list[tuple[int, str]]] = defaultdict(list)
    rows = s.execute(
        select(Punch.id, Punch.server_received_at, Punch.device_time, Punch.manifest_raw)
        .where(Punch.server_received_at >= now - timedelta(days=days))
        .order_by(Punch.id)
    )
    for pid, received, device_time, raw in rows:
        tc = services.time_check(SimpleNamespace(server_received_at=received, device_time=device_time, manifest_raw=raw))
        if tc.warning:
            by_day[_day(received)].append((pid, tc.warning))
    return [
        Problem(f"ora-telefono:{day.isoformat()}",
                f"{len(items)} timbrature del {day:%d/%m} con l'ora del telefono smentita: {_ids([i for i, _ in items])} "
                f"(#{items[0][0]}: {items[0][1]})")
        for day, items in sorted(by_day.items())
    ]


def _mails(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    """Mail di contestazione rifiutate o non ancora partite dopo alert_mail_after_min (per giorno di creazione).
    Niente indirizzi nei testi: solo numeri, ore e il tipo di errore."""
    old = now - timedelta(minutes=settings.alert_mail_after_min)
    rows = s.execute(
        select(ContestNotice.id, ContestNotice.created_at, ContestNotice.status, ContestNotice.error).where(
            (ContestNotice.status == "failed")
            | (ContestNotice.status.in_(("pending", "retrying")) & (ContestNotice.created_at < old))
        ).order_by(ContestNotice.id)
    ).all()
    by_day: dict[date, list] = defaultdict(list)
    for nid, created, status, error in rows:
        by_day[_day(created)].append((nid, status, (error or "").split(":", 1)[0]))
    out = []
    for day, items in sorted(by_day.items()):
        failed = [i for i in items if i[1] == "failed"]
        waiting = [i for i in items if i[1] != "failed"]
        parts = []
        if failed:
            parts.append(f"{len(failed)} rifiutate ({failed[-1][2] or '?'})")
        if waiting:
            parts.append(f"{len(waiting)} non ancora partite dopo {settings.alert_mail_after_min} minuti "
                         f"({waiting[-1][2] or 'in attesa'})")
        out.append(Problem(f"mail-contestazione:{day.isoformat()}",
                           f"mail di contestazione del {day:%d/%m}: {'; '.join(parts)}"))
    return out


def _new_devices(s: Session, now: datetime) -> list[Problem]:
    """Telefoni associati nelle ultime 24 ore: un avviso per ognuno (un'associazione con un codice intercettato si
    vede subito). Il problema sparisce da solo dopo un giorno o con la revoca. Niente nomi: utente, ora, id breve."""
    rows = s.execute(
        select(Device.id, Device.user_id, Device.created_at)
        .where(Device.created_at > now - timedelta(hours=24), Device.revoked_at.is_(None))
        .order_by(Device.created_at)
    ).all()
    return [
        Problem(f"telefono-nuovo:{did}", f"nuovo telefono associato all'utente #{uid} il {_local(created)} (id {did[:8]}): "
                                         "se non è tuo, revocalo dall'amministrazione")
        for did, uid, created in rows
    ]


def _checks(s: Session, settings: Settings, now: datetime) -> list[Problem]:
    out = []
    tl = services.get_json_setting(s, checks.TL_KEY)
    if tl:
        if tl.get("not_granted"):
            out.append(Problem("trusted-list", "Trusted List: provider non più qualificati: "
                                               f"{', '.join(tl['not_granted'])} (python -m timbro check-tl)"))
        last_ok = tl.get("last_success")
        too_old = timedelta(hours=settings.tl_check_interval_h) + timedelta(days=2)
        if tl.get("error") and (last_ok is None or now - datetime.fromisoformat(last_ok) > too_old):
            out.append(Problem("trusted-list-controllo", f"il controllo della Trusted List non riesce: {tl['error'][:200]}"))
    rev = services.get_json_setting(s, checks.REVOCATION_KEY)
    if rev:
        if rev.get("revoked"):
            # per giorno delle timbrature: una revoca nuova su altri giorni è un avviso nuovo
            ids = sorted({h["punch_id"] for h in rev["revoked"]})
            rows = s.execute(select(Punch.id, Punch.server_received_at).where(Punch.id.in_(ids))).all()
            for day, day_ids in _by_day(rows).items():
                out.append(Problem(f"attestazione-revocata:{day.isoformat()}",
                                   f"Google ha revocato chiavi usate nelle attestazioni delle timbrature del {day:%d/%m}: "
                                   f"{_ids(day_ids)}: quelle attestazioni non provano più l'integrità del telefono"))
        last_ok = rev.get("last_success")
        if rev.get("error") and (last_ok is None or now - datetime.fromisoformat(last_ok) > timedelta(days=3)):
            out.append(Problem("attestazione-revoche-controllo", f"il controllo della lista di revoca di Google non riesce: "
                                                                 f"{rev['error'][:200]}"))
    arch = services.get_json_setting(s, checks.ARCHIVE_KEY)
    if arch:
        if arch.get("ok") is False:
            errors = "; ".join(arch.get("errors", [])[:3])
            out.append(Problem("archivio", f"ARCHIVIO NON INTEGRO (verifica del {_local(datetime.fromisoformat(arch['at']))}): "
                                           f"{errors[:300]} — python -m timbro verify-chain"))
        started = datetime.fromisoformat(arch["started_at"]) if arch.get("started_at") else None
        ended = datetime.fromisoformat(arch["at"]) if arch.get("at") else None
        last_ok = datetime.fromisoformat(arch["last_success"]) if arch.get("last_success") else None
        if arch.get("error"):
            out.append(Problem("archivio-controllo", f"la verifica dell'archivio non riesce: {arch['error'][:200]}"))
        elif started and (ended is None or started > ended) and now - started > timedelta(hours=2):
            out.append(Problem("archivio-controllo", f"la verifica dell'archivio iniziata il {_local(started)} non si è "
                                                     "conclusa (processo riavviato o senza memoria?)"))
        elif last_ok and now - last_ok > timedelta(hours=36):
            out.append(Problem("archivio-controllo", f"l'archivio non viene verificato dal {_local(last_ok)}"))
    return out


def as_json(problems: list[Problem]) -> str:
    return json.dumps({"problemi": [{"chiave": p.key, "testo": p.text} for p in problems]}, ensure_ascii=False)

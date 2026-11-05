import functools
import hashlib
import json
import logging
import re
import secrets
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from timbro import attestation, chain, versione
from timbro.config import Settings
from timbro.db import CHAIN_LOCK, utcnow
from timbro.manifest import Manifest, ManifestError, canonical_pem, public_key_der
from timbro.models import Device, PairingCode, Punch, Setting, ShareLink, Stamp, User
from timbro.stamping.tsa import PROVIDERS

log = logging.getLogger(__name__)

# esito del controllo della lista di revoca di Google (checks.run_attestation_revocations), nella tabella settings
REVOCATION_KEY = "check_attestation_revocation"
BSSID_RE = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")  # come WifiAp in manifest.py


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def create_user(s: Session, username: str) -> User:
    user = User(username=username)
    s.add(user)
    s.commit()
    return user


def new_pairing_code(s: Session, user: User, settings: Settings) -> str:
    code = secrets.token_urlsafe(9)
    s.add(
        PairingCode(
            user_id=user.id,
            code_hash=token_hash(code),
            expires_at=utcnow() + timedelta(minutes=settings.pairing_code_minutes),
        )
    )
    s.commit()
    return code


class PairingError(Exception):
    pass


def register_device(
    s: Session, code: str, name: str, public_key_pem: str, admin_public_key_pem: str, attestation_chain_pem: str | None
) -> tuple[Device, str]:
    # si conservano in forma canonica (SubjectPublicKeyInfo DER, punto non compresso): è quella che entra nella
    # catena (public_key_der) e nel pacchetto di prova, così verifica.py e openssl ritrovano gli stessi byte
    public_key_pem = canonical_pem(public_key_pem)
    admin_public_key_pem = canonical_pem(admin_public_key_pem)
    if public_key_der(public_key_pem) == public_key_der(admin_public_key_pem):
        raise ManifestError("la chiave admin deve essere diversa da quella delle timbrature")
    now = utcnow()
    # consumo atomico del codice: due registrazioni parallele non possono usarlo entrambe
    user_id = s.scalar(
        update(PairingCode)
        .where(
            PairingCode.code_hash == token_hash(code),
            PairingCode.used_at.is_(None),
            PairingCode.expires_at > now,
        )
        .values(used_at=now)
        .returning(PairingCode.user_id)
    )
    if user_id is None:
        s.rollback()
        raise PairingError("codice di associazione non valido o scaduto")
    api_token = secrets.token_urlsafe(32)
    device = Device(
        id=str(uuid.uuid4()),
        user_id=user_id,
        name=name,
        public_key_pem=public_key_pem,
        admin_public_key_pem=admin_public_key_pem,
        attestation_chain_pem=attestation_chain_pem,
        api_token_hash=token_hash(api_token),
    )
    s.add(device)
    s.commit()
    return device, api_token


def device_by_token(s: Session, token: str) -> Device | None:
    d = s.scalar(select(Device).where(Device.api_token_hash == token_hash(token)))
    return d if d is not None and d.revoked_at is None else None


def _existing_punch(s: Session, device: Device, client_id: str, raw: bytes) -> Punch | None:
    existing = s.scalar(select(Punch).where(Punch.device_id == device.id, Punch.client_id == client_id))
    if existing is not None and existing.manifest_raw != raw:
        raise ValueError("client_id già usato con un manifest diverso")
    return existing


@functools.cache
def _attestation_roots(directory: str):
    return attestation.load_roots(Path(directory))


def _parse_device_time(value: str) -> datetime | None:
    try:
        t = datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None
    return t if t.tzinfo is not None else None


def check_attestation(
    s: Session, items: list[str] | None, raw: bytes, device_time: str, settings: Settings
) -> tuple[str | None, dict | None]:
    """(catena PEM, esito) dell'attestazione inviata con la timbratura. Mai un'eccezione: la timbratura si accetta
    comunque, l'esito dice cosa non va. I seriali già revocati vengono dall'ultima lista di Google scaricata
    (checks.run_attestation_revocations)."""
    if items is None:
        return None, None
    try:
        chain = attestation.decode_chain(items)
        pem = attestation.to_pem(chain)
    except Exception as e:
        return None, {"ok": False, "problems": [f"catena illeggibile: {type(e).__name__}: {e}"[:300]]}
    try:
        revoked = frozenset((get_json_setting(s, REVOCATION_KEY) or {}).get("revoked_serials") or [])
        result = attestation.verify(
            chain, raw, _attestation_roots(str(settings.attestation_roots_dir)), utcnow(),
            settings.attestation_package, settings.attestation_signing_cert_sha256,
            revoked_serials=revoked, punch_time=_parse_device_time(device_time),
        ).as_dict()
        json.dumps(result)  # deve potersi salvare
    except Exception as e:
        log.exception("verifica dell'attestazione")
        result = {"ok": False, "problems": [f"verifica non riuscita: {type(e).__name__}: {e}"[:300]]}
    return pem, result


def create_punch(
    s: Session, device: Device, m: Manifest, raw: bytes, signature: bytes, settings: Settings,
    attestation_items: list[str] | None = None,
) -> tuple[Punch, bool]:
    """Registra la timbratura ed accoda le marche. Idempotente su (device, client_id).

    La catena si estende sotto un lock "advisory" di Postgres legato alla transazione: le timbrature
    si accodano una alla volta anche fra thread (e processi) diversi, e il lock si libera al commit.
    prev_chain_hash UNIQUE impedisce comunque una biforcazione.
    """
    s.execute(select(func.pg_advisory_xact_lock(CHAIN_LOCK)))
    existing = _existing_punch(s, device, m.client_id, raw)
    if existing is not None:
        s.commit()  # libera il lock
        return existing, False
    last = s.scalar(select(Punch).order_by(Punch.id.desc()).limit(1))
    prev = last.chain_hash if last else chain.GENESIS
    received_at = utcnow()
    chain_hash = chain.link(prev, raw, signature, public_key_der(device.public_key_pem), received_at)
    punch = Punch(
        user_id=device.user_id,
        device_id=device.id,
        client_id=m.client_id,
        kind=m.kind,
        sha256=m.sha256,
        size=m.size,
        duration_ms=m.duration_ms,
        device_time=m.device_time,
        location_json=m.location.model_dump_json() if m.location else None,
        manifest_raw=raw,
        device_signature=signature,
        server_received_at=received_at,
        prev_chain_hash=prev,
        chain_hash=chain_hash,
        server_version=versione.server_commit(),
    )
    att_pem, att_result = check_attestation(s, attestation_items, raw, m.device_time, settings)
    punch.attestation_pem = att_pem
    punch.attestation_json = json.dumps(att_result) if att_result is not None else None
    s.add(punch)
    try:
        s.flush()
    except IntegrityError:
        # un'altra richiesta con lo stesso client_id ha vinto: si restituisce quella
        s.rollback()
        existing = _existing_punch(s, device, m.client_id, raw)
        if existing is None:
            raise
        return existing, False
    add_stamps(s, settings, "video", m.sha256, punch_id=punch.id)
    add_stamps(s, settings, "record", chain_hash, punch_id=punch.id)
    if att_pem:
        add_stamps(s, settings, "attest", hashlib.sha256(att_pem.encode()).hexdigest(), punch_id=punch.id)
    s.commit()
    return punch, True


def add_stamps(s: Session, settings: Settings, target: str, digest: str, *, punch_id=None, chain_day=None) -> None:
    """Accoda le marche per un'impronta secondo la configurazione del target."""
    providers = {
        "video": settings.tsa_providers,
        "record": settings.record_tsa_providers,
        "attest": settings.record_tsa_providers,
    }.get(target, [])
    for name in providers:
        p = PROVIDERS[name]
        s.add(
            Stamp(
                target=target, punch_id=punch_id, chain_day=chain_day, provider=name,
                kind="rfc3161", qualified=p.qualified, digest=digest,
            )
        )
    if settings.ots_enabled:
        s.add(Stamp(target=target, punch_id=punch_id, chain_day=chain_day, provider="ots", kind="ots", digest=digest))


LOCAL_TZ = ZoneInfo("Europe/Rome")
HISTORY_MAX_DAYS = 62


# l'ora del telefono si sposta dalle impostazioni senza nessuna modifica al sistema: vale solo se la conferma la
# ricezione immediata o un orologio che l'utente non controlla (rete, satelliti), scritto nel manifest firmato
SENT_AT_ONCE = timedelta(minutes=2)
CLOCK_TOLERANCE = timedelta(minutes=2)
DEVICE_AHEAD_MAX = timedelta(minutes=5)
QUEUE_MAX = timedelta(days=14)
CLOCK_NAMES = (("gnss", "satellitare"), ("network", "di rete"))


@dataclass(frozen=True)
class PunchTime:
    """Ora della timbratura e da cosa è confermata. warning: l'ora del telefono è smentita (orologio spostato o
    avanti): si usa un'altra ora e parte l'avviso. confirmed_by None e warning None: non confermata (timbratura
    arrivata dopo, senza orologi nel manifest), vale l'ora di ricezione."""

    time: datetime
    confirmed_by: str | None = None
    warning: str | None = None


def _manifest_clocks(raw: bytes) -> dict:
    try:
        clocks = json.loads(raw).get("clocks")
    except (ValueError, AttributeError):
        return {}
    return clocks if isinstance(clocks, dict) else {}


def _minutes(d: timedelta) -> str:
    m = round(abs(d.total_seconds()) / 60)
    return f"{m} min" if m else f"{round(abs(d.total_seconds()))} s"


def time_check(p: Punch) -> PunchTime:
    received = p.server_received_at
    t = _parse_device_time(p.device_time)
    if t is None:
        return PunchTime(received, warning="ora del telefono illeggibile: vale l'ora di ricezione")
    clocks = _manifest_clocks(p.manifest_raw)
    refs = [(label, c) for key, label in CLOCK_NAMES if (c := _parse_device_time(clocks.get(key))) is not None]
    bad = [(label, c) for label, c in refs if abs(t - c) > CLOCK_TOLERANCE]
    if bad:
        label, c = bad[0]
        note = f"ora del telefono diversa di {_minutes(t - c)} dall'orologio {label}"
        good = [g for g, _ in refs if g not in {x for x, _ in bad}]
        if good:  # un altro orologio la conferma: si tiene, ma il disaccordo si segnala
            return PunchTime(t, confirmed_by="dall'orologio " + good[0], warning=note)
        if received - QUEUE_MAX <= c <= received + DEVICE_AHEAD_MAX:
            return PunchTime(c, warning=f"{note}: vale l'orologio {label}")
        return PunchTime(received, warning=f"{note}: vale l'ora di ricezione")
    if t > received + DEVICE_AHEAD_MAX:
        return PunchTime(received, warning=f"ora del telefono avanti di {_minutes(t - received)} sulla ricezione: "
                                           "vale l'ora di ricezione")
    if received - t > QUEUE_MAX:
        return PunchTime(received, warning="ora del telefono più vecchia di 14 giorni: vale l'ora di ricezione")
    if refs:
        return PunchTime(t, confirmed_by="dall'orologio " + " e ".join(label for label, _ in refs))
    if received - t <= SENT_AT_ONCE:
        return PunchTime(t, confirmed_by="dalla ricezione immediata")
    return PunchTime(received)


def punch_time(p: Punch) -> datetime:
    """Ora della timbratura da mostrare e da usare per le ore: quella del telefono quando è confermata (time_check),
    altrimenti quella di un orologio che la smentisce o la ricezione del server."""
    return time_check(p).time


def history(s: Session, user_id: int, until: date, days: int) -> dict:
    """Storico per l'app, per giorno (ora italiana), dal giorno `until` indietro di `days` giorni. Solo quello che
    serve alla vista per giorno: niente posizioni, reti Wi-Fi o note, che restano nel dettaglio dietro la firma
    biometrica (API admin). Giorni completi: la paginazione è per giorno, non per timbratura."""
    days = max(1, min(days, HISTORY_MAX_DAYS))
    start_day = until - timedelta(days=days - 1)
    start = datetime.combine(start_day, time.min, LOCAL_TZ)
    end = datetime.combine(until + timedelta(days=1), time.min, LOCAL_TZ)
    # l'ora mostrata è al massimo 14 giorni prima della ricezione (punch_time): si leggono anche quelle ricevute dopo
    rows = s.scalars(
        select(Punch)
        .where(Punch.user_id == user_id, Punch.server_received_at >= start - timedelta(minutes=5),
               Punch.server_received_at < end + timedelta(days=14))
        .order_by(Punch.id)
    ).all()
    by_day: dict[date, list[tuple[datetime, Punch]]] = {}
    older = False  # timbrature di giorni precedenti alla finestra
    for p in rows:
        t = punch_time(p).astimezone(LOCAL_TZ)
        if start_day <= t.date() <= until:
            by_day.setdefault(t.date(), []).append((t, p))
        older |= t.date() < start_day
    older = older or s.scalar(
        select(Punch.id).where(Punch.user_id == user_id, Punch.server_received_at < start).limit(1)
    ) is not None
    return {
        "days": [
            {"date": d.isoformat(), "punches": [_history_item(p, t) for t, p in sorted(items, key=lambda x: (x[0], x[1].id))]}
            for d, items in sorted(by_day.items(), reverse=True)
        ],
        # giorno da cui continuare; un giorno senza timbrature nella finestra non ferma la paginazione
        "next_until": (start_day - timedelta(days=1)).isoformat() if older else None,
    }


def _history_item(p: Punch, t: datetime) -> dict:
    qualified = any(st.target == "video" and st.qualified and st.status == "complete" for st in p.stamps)
    att = json.loads(p.attestation_json) if p.attestation_json else None
    if p.video_deleted_at is not None:
        video = "cancellato"
    elif p.video_received_at is not None:
        video = "ricevuto"
    else:
        video = "mancante"
    return {
        "id": p.id,
        "client_id": p.client_id,
        "kind": p.kind,
        "time": t.isoformat(),
        "server_received_at": p.server_received_at.isoformat(),
        "video": video,
        "qualified_stamp": qualified,
        "attestation_ok": att.get("ok", False) if att is not None else None,
        "contested": p.contested,
        "keep": p.keep,
    }


def punch_status(p: Punch) -> dict:
    return {
        "id": p.id,
        "client_id": p.client_id,
        "kind": p.kind,
        "sha256": p.sha256,
        "server_received_at": p.server_received_at.isoformat(),
        "chain_hash": p.chain_hash,
        "video_received": p.video_received_at is not None,
        "stamps": [
            {
                "target": st.target,
                "provider": st.provider,
                "qualified": st.qualified,
                "status": st.status,
                "gen_time": st.gen_time.isoformat() if st.gen_time else None,
            }
            for st in p.stamps
        ],
        "location": json.loads(p.location_json) if p.location_json else None,
        # l'app lo mostra subito: un'attestazione che non va si vede alla timbratrice, non settimane dopo
        "attestation": _attestation_summary(p),
    }


def _attestation_summary(p: Punch) -> dict | None:
    if p.attestation_json is None:
        return None
    r = json.loads(p.attestation_json)
    return {"ok": r.get("ok", False), "problems": r.get("problems", [])}


def wifi_aps(manifest_raw: bytes) -> list[dict]:
    """Reti del manifest, solo quelle con un BSSID ben formato: anche un manifest di forma inattesa (salvato prima di
    una modifica del formato) non deve rompere pacchetto e dettaglio."""
    try:
        wifi = json.loads(manifest_raw).get("wifi") or {}
        aps = wifi.get("aps") if isinstance(wifi, dict) else None
        if not isinstance(aps, list):
            return []
        return [a for a in aps if isinstance(a, dict) and isinstance(a.get("bssid"), str) and BSSID_RE.match(a["bssid"])]
    except (ValueError, AttributeError):
        return []


def is_random_mac(bssid: str) -> bool:
    """Indirizzo amministrato localmente (bit 0x02 del primo byte): hotspot dei telefoni e MAC casuali, non la sede."""
    return bool(int(bssid[:2], 16) & 0x02)


def wifi_summary(s: Session, p: Punch) -> dict | None:
    """Reti viste alla timbratura e in quante altre timbrature dello stesso utente compaiono: le reti della sede si
    ripetono giorno dopo giorno. Gli hotspot personali (MAC casuali) si scartano."""
    aps = wifi_aps(p.manifest_raw)
    if not aps:
        return None
    fixed = [a for a in aps if not is_random_mac(a["bssid"])]
    seen: Counter[str] = Counter()
    for raw in s.scalars(select(Punch.manifest_raw).where(Punch.user_id == p.user_id, Punch.id != p.id)):
        seen.update({a["bssid"] for a in wifi_aps(raw) if not is_random_mac(a["bssid"])})
    return {
        "reti": len(fixed),
        "gia_viste_in_altre_timbrature": sum(1 for a in fixed if seen[a["bssid"]]),
        "scartate_mac_casuali": len(aps) - len(fixed),
        "dettaglio": [{**a, "viste_in_altre_timbrature": seen[a["bssid"]]} for a in fixed],
    }


# --- gestione (API admin) ---------------------------------------------------------------------

RETENTION_KEY = "retention_days"


def get_json_setting(s: Session, key: str) -> dict | None:
    v = s.get(Setting, key)
    return json.loads(v.value) if v else None


def set_json_setting(s: Session, key: str, value: dict) -> None:
    s.merge(Setting(key=key, value=json.dumps(value, ensure_ascii=False)))
    s.commit()


def get_retention_days(s: Session, settings: Settings) -> int:
    v = s.get(Setting, RETENTION_KEY)
    return int(v.value) if v else settings.retention_days_default


def set_retention_days(s: Session, days: int) -> None:
    if not 7 <= days <= 3650:
        raise ValueError("conservazione fra 7 e 3650 giorni")
    s.merge(Setting(key=RETENTION_KEY, value=str(days)))
    s.commit()


def delete_video(s: Session, settings: Settings, punch_id: int, reason: str) -> bool:
    """Cancella solo il file video: hash, manifest, marche e catena restano per sempre.

    La marcatura come cancellato è un UPDATE condizionato, ricontrollato nello stesso istante: un
    flag "tieni" o "contestata" messo nel frattempo impedisce la cancellazione. Il file si
    rimuove solo dopo il commit, insieme ai pacchetti di prova in cache che lo contengono.
    """
    rel = s.scalar(
        update(Punch)
        .where(
            Punch.id == punch_id,
            Punch.video_path.is_not(None),
            Punch.video_deleted_at.is_(None),
            Punch.keep.is_(False),
            Punch.contested.is_(False),
        )
        .values(video_deleted_at=utcnow(), video_deleted_reason=reason)
        .returning(Punch.video_path)
    )
    s.commit()
    if rel is None:
        return False
    (settings.videos_dir / rel).unlink(missing_ok=True)
    from timbro import evidence

    evidence.drop_cache(settings, punch_id)
    return True


def shared_punch_ids(s: Session, now=None) -> set[int]:
    """Timbrature incluse in link di condivisione ancora validi (non scaduti né revocati)."""
    now = now or utcnow()
    ids: set[int] = set()
    for raw in s.scalars(select(ShareLink.punch_ids_json).where(ShareLink.revoked_at.is_(None), ShareLink.expires_at > now)):
        ids.update(json.loads(raw))
    return ids


def retention_sweep(s: Session, settings: Settings, now=None) -> int:
    """Cancella i video più vecchi della conservazione, salvo quelli da tenere, contestati o inclusi in un link di
    condivisione ancora valido (l'avvocato deve poterli vedere fino alla scadenza del link)."""
    now = now or utcnow()
    cutoff = now - timedelta(days=get_retention_days(s, settings))
    expired = s.scalars(
        select(Punch.id).where(
            Punch.video_path.is_not(None),
            Punch.video_deleted_at.is_(None),
            Punch.keep.is_(False),
            Punch.contested.is_(False),
            Punch.server_received_at < cutoff,
        )
    ).all()
    shared = shared_punch_ids(s, now)
    return sum(delete_video(s, settings, pid, "retention") for pid in expired if pid not in shared)


def cleanup_partial_uploads(settings: Settings, older_than: timedelta | None = None) -> int:
    """Cancella i file .part degli upload interrotti (arresto brusco, memoria). All'avvio tutti (un solo processo:
    nessun upload in corso), poi ogni ora quelli più vecchi di older_than."""
    import time

    limit = time.time() - older_than.total_seconds() if older_than else None
    n = 0
    for f in settings.videos_dir.rglob("*.part") if settings.videos_dir.exists() else []:
        try:
            if limit is None or f.stat().st_mtime < limit:
                f.unlink()
                n += 1
        except FileNotFoundError:
            pass
    return n


def cleanup_files(s: Session, settings: Settings, now=None) -> int:
    """File rimasti su disco: video di timbrature già segnate come cancellate (processo ucciso fra il commit e la
    cancellazione del file) e upload interrotti più vecchi di un giorno."""
    n = cleanup_partial_uploads(settings, timedelta(days=1))
    from timbro import evidence

    for pid, rel in s.execute(select(Punch.id, Punch.video_path).where(Punch.video_deleted_at.is_not(None), Punch.video_path.is_not(None))):
        f = settings.videos_dir / rel
        if f.exists():
            f.unlink(missing_ok=True)
            evidence.drop_cache(settings, pid)
            n += 1
    return n


def create_share(
    s: Session, settings: Settings, device: Device, punch_ids: list[int], label: str, days: int, allow_evidence_download: bool
) -> tuple[ShareLink, str]:
    if not 1 <= days <= settings.share_max_days:
        raise ValueError(f"durata del link fra 1 e {settings.share_max_days} giorni")
    ids = sorted(set(punch_ids))
    found = s.scalars(select(Punch).where(Punch.id.in_(ids), Punch.user_id == device.user_id)).all()
    if not ids or len(found) != len(ids):
        raise ValueError("timbrature inesistenti o non tue")
    # un link mostra sempre giornate intere, come le contestazioni: una timbratura senza le altre dello stesso giorno
    # non dice se la giornata torna. Si aggiungono quelle esistenti ora; il link non cresce con le timbrature future
    days_ = {punch_time(p).astimezone(LOCAL_TZ).date() for p in found}
    ids = sorted(set(ids) | {p.id for d in days_ for p in day_punches(s, device.user_id, d)})
    token = secrets.token_urlsafe(32)
    share = ShareLink(
        user_id=device.user_id,
        token_hash=token_hash(token),
        label=label,
        punch_ids_json=json.dumps(ids),
        allow_evidence_download=allow_evidence_download,
        created_by_device=device.id,
        expires_at=utcnow() + timedelta(days=days),
    )
    s.add(share)
    s.commit()
    return share, token


def share_by_token(s: Session, token: str) -> ShareLink | None:
    sh = s.scalar(select(ShareLink).where(ShareLink.token_hash == token_hash(token)))
    if sh is None or sh.revoked_at is not None or sh.expires_at < utcnow():
        return None
    return sh


def revoke_device(s: Session, device_id: str, keep_shares: bool = False) -> tuple[Device, int]:
    """Revoca un telefono (token e chiavi) e, salvo keep_shares, i link che ha creato."""
    d = s.get(Device, device_id)
    if d is None:
        raise ValueError("dispositivo inesistente")
    now = utcnow()
    if d.revoked_at is None:
        d.revoked_at = now
    n = 0
    if not keep_shares:
        n = s.execute(
            update(ShareLink)
            .where(ShareLink.created_by_device == device_id, ShareLink.revoked_at.is_(None))
            .values(revoked_at=now)
        ).rowcount
    s.commit()
    return d, n


def share_url(settings: Settings, token: str) -> str:
    return f"{settings.public_base_url.rstrip('/')}/s/{token}"


# --- profilo e contestazioni ----------------------------------------------------------------------


def get_profile(s: Session, user_id: int) -> dict:
    """Nome, matricola e destinatari delle mail di contestazione (tabelle profiles e profile_recipients)."""
    from timbro import accounts

    return accounts.get_profile(s, user_id)


def set_profile(s: Session, user_id: int, full_name: str | None, employee_number: str | None, recipients: list[str],
                email: str | None = None) -> dict:
    from timbro import accounts

    return accounts.set_profile(s, user_id, full_name, employee_number, recipients, email)


def day_punches(s: Session, user_id: int, day: date) -> list[Punch]:
    """Timbrature dell'utente in quel giorno (ora italiana, ora della timbratura come in history())."""
    start = datetime.combine(day, time.min, LOCAL_TZ)
    end = datetime.combine(day + timedelta(days=1), time.min, LOCAL_TZ)
    rows = s.scalars(
        select(Punch)
        .where(Punch.user_id == user_id, Punch.server_received_at >= start - timedelta(minutes=5),
               Punch.server_received_at < end + timedelta(days=14))
        .order_by(Punch.id)
    ).all()
    return sorted((p for p in rows if punch_time(p).astimezone(LOCAL_TZ).date() == day), key=lambda p: (punch_time(p), p.id))


def get_schedules(s: Session, user_id: int):
    """Orario dell'utente già risolto (assegnazioni + versioni delle articolazioni), per il motore delle ore."""
    from timbro import accounts

    return accounts.resolved_schedules(s, user_id)


def day_hours(s: Session, user_id: int, day: date, punches: list[Punch] | None = None, now: datetime | None = None):
    """(esito del calcolo, orario in vigore) delle ore valide del giorno, con l'orario dell'utente."""
    from timbro import orario

    punches = day_punches(s, user_id, day) if punches is None else punches
    marks = [orario.Mark(p.kind, punch_time(p).astimezone(LOCAL_TZ).replace(tzinfo=None)) for p in punches]
    schedule = get_schedules(s, user_id).at(day)
    local_now = now.astimezone(LOCAL_TZ).replace(tzinfo=None) if now else None
    return orario.compute(marks, schedule, local_now), schedule


def hours_summary(result, schedule) -> dict | None:
    if schedule is None:
        return None
    notes = lambda ns: [{"code": n.code, "params": n.params} for n in ns]  # noqa: E731
    return {
        "worked_min": result.worked_min, "due_min": result.due_min, "balance_min": result.balance_min,
        "complete": result.complete, "open": result.open,
        "adjustments": notes(result.adjustments), "anomalies": notes(result.anomalies),
    }


class ContestError(Exception):
    """Contestazione impossibile così com'è (409): manca la configurazione della mail o il profilo."""


def contest(
    s: Session, settings: Settings, device: Device, punch: Punch | None, days: int, recipients: list[str] | None,
    note: str | None, day: date | None = None,
):
    """Contesta una timbratura (punch) o tutta una giornata (day, punch None): crea il link con tutte le timbrature
    del giorno, segna la timbratura come contestata e, se ci sono destinatari, accoda la mail (la manda il worker).
    Restituisce (link, token, avviso o None, esito del calcolo delle ore, orario)."""
    from timbro import mailer
    from timbro.models import ContestNotice

    profile = get_profile(s, device.user_id)
    to = profile["contest_recipients"] if recipients is None else list(dict.fromkeys(r.strip() for r in recipients))
    bad = [r for r in to if not mailer.valid_email(r)]
    if bad:
        raise ValueError(f"indirizzi non validi: {', '.join(bad)}")
    if len(to) > mailer.MAX_RECIPIENTS:
        raise ValueError(f"al massimo {mailer.MAX_RECIPIENTS} destinatari")
    if to and not mailer.configured(settings):
        raise ContestError("invio mail non configurato sul server (SMTP_HOST, SMTP_USER, SMTP_PASSWORD)")
    if to and not (profile["full_name"] and profile["employee_number"]):
        raise ContestError("imposta nome e matricola nel profilo prima di inviare la mail")

    local = punch_time(punch).astimezone(LOCAL_TZ) if punch is not None else None
    day = local.date() if local is not None else day
    punches = day_punches(s, device.user_id, day)
    if not punches:
        raise ValueError("nessuna timbratura in quel giorno")
    ids = [p.id for p in punches]
    hours, schedule = day_hours(s, device.user_id, day, punches)
    share, token = create_share(s, settings, device, ids, f"Contestazione del {day:%d/%m/%Y}", days, True)
    share.note = note
    share.contest_day = day
    if punch is not None:
        s.get(Punch, punch.id).contested = True
    notice = None
    if to:
        mail = mailer.compose(
            profile["full_name"], profile["employee_number"], day,
            (punch.kind, local) if punch is not None else None,
            [(p.kind, punch_time(p).astimezone(LOCAL_TZ)) for p in punches],
            hours if schedule is not None else None,
            share_url(settings, token), share.expires_at.astimezone(LOCAL_TZ).date(), note,
        )
        notice = ContestNotice(
            user_id=device.user_id, punch_id=punch.id if punch is not None else None, day=day, share_id=share.id,
            recipients_json=json.dumps(to), note=note, subject=mail.subject, body=mail.body,
            reply_to=profile.get("email"),
        )
        s.add(notice)
    s.commit()
    return share, token, notice, hours, schedule


def notice_summary(n) -> dict:
    return {
        "id": n.id,
        "status": n.status,
        "recipients": json.loads(n.recipients_json),
        "subject": n.subject,
        "created_at": n.created_at.isoformat(),
        "sent_at": n.sent_at.isoformat() if n.sent_at else None,
        "error": n.error,
        "share_id": n.share_id,
        "day": n.day.isoformat(),
        "punch_id": n.punch_id,
    }

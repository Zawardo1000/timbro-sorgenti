"""Utenti, profili, articolazioni orarie e assegnazioni (tabelle profiles, profile_recipients, schedules,
schedule_versions, schedule_days, profile_schedules).

Due livelli di gestione, sempre con la firma biometrica del telefono (admin_auth.py):
- l'utente gestisce il proprio profilo (nome, matricola, destinatari) e vede il proprio orario;
- l'amministratore (users.role = "admin") gestisce utenti, telefoni, articolazioni e assegnazioni di tutti.

All'app l'orario arriva già risolto (resolved_schedules): un solo elenco di versioni datate che unisce le
assegnazioni del profilo e le versioni delle articolazioni, nella forma che il motore delle ore conosce
(orario.Schedules). Così il calcolo, in Python e in Kotlin, non sa niente di assegnazioni e condivisioni.
"""

from datetime import date

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from timbro import mailer, orario
from timbro.models import (
    Device,
    Profile,
    ProfileRecipient,
    ProfileSchedule,
    Schedule,
    ScheduleDay,
    ScheduleVersion,
    User,
)

ROLES = ("user", "admin")


# --- profilo ---------------------------------------------------------------------------------------------------------


def _profile(s: Session, user_id: int) -> Profile:
    p = s.get(Profile, user_id)
    if p is None:
        p = Profile(user_id=user_id)
        s.add(p)
        s.flush()
    return p


def get_profile(s: Session, user_id: int) -> dict:
    p = s.get(Profile, user_id)
    return {
        "full_name": p.full_name if p else None,
        "employee_number": p.employee_number if p else None,
        "email": p.email if p else None,
        "contest_recipients": [r.email for r in p.recipients] if p else [],
    }


def clean_recipients(recipients: list[str]) -> list[str]:
    clean = list(dict.fromkeys(r.strip() for r in recipients))
    bad = [r for r in clean if not mailer.valid_email(r)]
    if bad:
        raise ValueError(f"indirizzi non validi: {', '.join(bad)}")
    if len(clean) > mailer.MAX_RECIPIENTS:
        raise ValueError(f"al massimo {mailer.MAX_RECIPIENTS} destinatari")
    return clean


def set_profile(s: Session, user_id: int, full_name: str | None, employee_number: str | None,
                recipients: list[str], email: str | None = None) -> dict:
    clean = clean_recipients(recipients)
    email = (email or "").strip() or None
    if email is not None and not mailer.valid_email(email):
        raise ValueError("indirizzo personale non valido")
    p = _profile(s, user_id)
    p.full_name = (full_name or "").strip() or None
    p.employee_number = (employee_number or "").strip() or None
    p.email = email
    p.recipients.clear()
    s.flush()  # prima le cancellazioni: lo stesso indirizzo può tornare (vincolo unico su utente e indirizzo)
    p.recipients.extend(ProfileRecipient(email=e, position=i) for i, e in enumerate(clean))
    s.commit()
    return get_profile(s, user_id)


# --- articolazioni orarie ---------------------------------------------------------------------------------------


def version_model(v: ScheduleVersion, valid_from: date | None = None) -> orario.Schedule:
    return orario.Schedule(
        valid_from=valid_from or v.valid_from,
        days={d.weekday: orario.DayRule(due_min=d.due_min, pause=d.pause) for d in v.days},
        entry_from=v.entry_from, entry_to=v.entry_to, pause_from=v.pause_from, pause_to=v.pause_to,
        pause_counted_min=v.pause_counted_min, pause_terminal_min=v.pause_terminal_min,
        no_pause_after_min=v.no_pause_after_min, no_pause_deduct_min=v.no_pause_deduct_min,
    )


def resolved_schedules(s: Session, user_id: int) -> orario.Schedules:
    """Versioni in vigore per l'utente, giorno per giorno: per ogni assegnazione (dal suo inizio alla successiva) la
    versione dell'articolazione in vigore all'inizio e quelle che cominciano dopo."""
    p = s.get(Profile, user_id)
    if p is None or not p.assignments:
        return orario.Schedules()
    out: list[orario.Schedule] = []
    assignments = sorted(p.assignments, key=lambda a: a.valid_from)
    for i, a in enumerate(assignments):
        end = assignments[i + 1].valid_from if i + 1 < len(assignments) else None
        versions = sorted(a.schedule.versions, key=lambda v: v.valid_from)
        current = [v for v in versions if v.valid_from <= a.valid_from]
        if current:
            out.append(version_model(current[-1], a.valid_from))
        out += [version_model(v) for v in versions if a.valid_from < v.valid_from and (end is None or v.valid_from < end)]
    return orario.Schedules(versions=out)


def schedule_out(sch: Schedule) -> dict:
    return {
        "id": sch.id,
        "name": sch.name,
        "versions": [version_model(v).model_dump(mode="json") for v in sorted(sch.versions, key=lambda v: v.valid_from)],
    }


def list_schedules(s: Session) -> list[dict]:
    return [schedule_out(x) for x in s.scalars(select(Schedule).order_by(Schedule.name))]


def _version_row(v: orario.Schedule) -> ScheduleVersion:
    row = ScheduleVersion(
        valid_from=v.valid_from, entry_from=v.entry_from, entry_to=v.entry_to, pause_from=v.pause_from,
        pause_to=v.pause_to, pause_counted_min=v.pause_counted_min, pause_terminal_min=v.pause_terminal_min,
        no_pause_after_min=v.no_pause_after_min, no_pause_deduct_min=v.no_pause_deduct_min,
    )
    row.days = [ScheduleDay(weekday=k, due_min=r.due_min, pause=r.pause) for k, r in sorted(v.days.items())]
    return row


def _schedule(s: Session, schedule_id: int) -> Schedule:
    sch = s.get(Schedule, schedule_id)
    if sch is None:
        raise LookupError("articolazione inesistente")
    return sch


def _name(s: Session, name: str, exclude_id: int | None = None) -> str:
    name = name.strip()
    if not name:
        raise ValueError("serve un nome")
    clash = s.scalar(select(Schedule.id).where(func.lower(Schedule.name) == name.lower()))
    if clash is not None and clash != exclude_id:
        raise ValueError("esiste già un'articolazione con questo nome")
    return name


def create_schedule(s: Session, name: str, version: orario.Schedule) -> dict:
    sch = Schedule(name=_name(s, name))
    sch.versions = [_version_row(version)]
    s.add(sch)
    s.commit()
    return schedule_out(sch)


def rename_schedule(s: Session, schedule_id: int, name: str) -> dict:
    sch = _schedule(s, schedule_id)
    sch.name = _name(s, name, schedule_id)
    s.commit()
    return schedule_out(sch)


def put_version(s: Session, schedule_id: int, version: orario.Schedule) -> dict:
    """Aggiunge una versione datata, o sostituisce quella con la stessa data."""
    sch = _schedule(s, schedule_id)
    old = next((v for v in sch.versions if v.valid_from == version.valid_from), None)
    if old is not None:
        sch.versions.remove(old)
        s.flush()
    sch.versions.append(_version_row(version))
    s.commit()
    s.refresh(sch)
    return schedule_out(sch)


def delete_version(s: Session, schedule_id: int, valid_from: date) -> dict:
    sch = _schedule(s, schedule_id)
    old = next((v for v in sch.versions if v.valid_from == valid_from), None)
    if old is None:
        raise LookupError("versione inesistente")
    if len(sch.versions) == 1:
        raise ValueError("un'articolazione deve avere almeno una versione")
    sch.versions.remove(old)
    s.commit()
    return schedule_out(sch)


def delete_schedule(s: Session, schedule_id: int) -> None:
    sch = _schedule(s, schedule_id)
    if s.scalar(select(ProfileSchedule.id).where(ProfileSchedule.schedule_id == schedule_id).limit(1)) is not None:
        raise ValueError("articolazione assegnata a qualcuno: togli prima le assegnazioni")
    s.delete(sch)
    s.commit()


def assignments_out(s: Session, user_id: int) -> list[dict]:
    p = s.get(Profile, user_id)
    return [
        {"schedule_id": a.schedule_id, "schedule_name": a.schedule.name, "valid_from": a.valid_from.isoformat()}
        for a in sorted(p.assignments, key=lambda a: a.valid_from)
    ] if p else []


def set_assignments(s: Session, user_id: int, items: list[tuple[int, date]]) -> list[dict]:
    """Sostituisce le assegnazioni del profilo: [(articolazione, dal giorno)]."""
    if len({d for _, d in items}) != len(items):
        raise ValueError("due assegnazioni con la stessa data")
    for sid, _ in items:
        _schedule(s, sid)
    p = _profile(s, user_id)
    p.assignments.clear()
    s.flush()
    p.assignments.extend(ProfileSchedule(schedule_id=sid, valid_from=d) for sid, d in sorted(items, key=lambda x: x[1]))
    s.commit()
    s.expire(p)
    return assignments_out(s, user_id)


# --- utenti (amministratore) --------------------------------------------------------------------------------------


def user_out(s: Session, u: User) -> dict:
    devices = s.scalars(select(Device).where(Device.user_id == u.id).order_by(Device.created_at)).all()
    return {
        "id": u.id,
        "username": u.username,
        "role": u.role,
        "profile": get_profile(s, u.id),
        "assignments": assignments_out(s, u.id),
        "devices": [
            {"id": d.id, "name": d.name, "created_at": d.created_at.isoformat(),
             "revoked_at": d.revoked_at.isoformat() if d.revoked_at else None}
            for d in devices
        ],
    }


def list_users(s: Session) -> list[dict]:
    return [user_out(s, u) for u in s.scalars(select(User).order_by(User.id))]


def set_role(s: Session, user_id: int, role: str) -> User:
    if role not in ROLES:
        raise ValueError(f"ruolo fra {', '.join(ROLES)}")
    u = s.get(User, user_id)
    if u is None:
        raise LookupError("utente inesistente")
    if u.role == "admin" and role != "admin":
        admins = s.scalar(select(func.count()).select_from(User).where(User.role == "admin"))
        if admins <= 1:
            raise ValueError("è l'ultimo amministratore: prima nominane un altro")
    u.role = role
    s.commit()
    return u

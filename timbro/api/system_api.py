"""Amministrazione di sistema (/api/v1/admin/system): utenti, ruoli, telefoni, articolazioni orarie e assegnazioni.

Come l'API admin dell'utente (token del telefono + firma con la chiave biometrica, admin_auth.py), ma solo per chi ha
il ruolo "admin". L'utente normale gestisce soltanto le proprie timbrature e il proprio profilo.
"""

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from timbro import accounts, orario, services
from timbro.api.admin_auth import AdminDep
from timbro.models import Device, User
from timbro.validation import NoNulStr

router = APIRouter(prefix="/api/v1/admin/system")


def get_system_admin(request: Request, device: AdminDep) -> Device:
    with request.app.state.db.session() as s:
        u = s.get(User, device.user_id)
        if u is None or u.role != "admin":
            raise HTTPException(403, "serve il ruolo di amministratore")
    return device


SystemAdminDep = Annotated[Device, Depends(get_system_admin)]


def _db(request: Request):
    return request.app.state.db


def _call(fn, *args):
    """Errori del dominio come risposte HTTP: inesistente 404, non valido 422."""
    try:
        return fn(*args)
    except LookupError as e:
        raise HTTPException(404, str(e)) from e
    except ValueError as e:
        raise HTTPException(422, str(e)) from e


# --- utenti e telefoni --------------------------------------------------------------------------------------------


class UserIn(BaseModel):
    username: NoNulStr = Field(min_length=1, max_length=64, pattern=r"^[a-z0-9._-]+$")
    role: str = "user"
    full_name: NoNulStr | None = Field(default=None, max_length=100)
    employee_number: NoNulStr | None = Field(default=None, max_length=40)


@router.get("/users")
def list_users(request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        return accounts.list_users(s)


@router.post("/users")
def create_user(body: UserIn, request: Request, device: SystemAdminDep):
    from sqlalchemy import select

    with _db(request).session() as s:
        if s.scalar(select(User.id).where(User.username == body.username)) is not None:
            raise HTTPException(409, "nome utente già usato")
        if body.role not in accounts.ROLES:
            raise HTTPException(422, f"ruolo fra {', '.join(accounts.ROLES)}")
        u = services.create_user(s, body.username)
        u.role = body.role
        s.commit()
        accounts.set_profile(s, u.id, body.full_name, body.employee_number, [])
        return accounts.user_out(s, u)


class RoleIn(BaseModel):
    role: str


@router.put("/users/{user_id}/role")
def set_role(user_id: int, body: RoleIn, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        u = _call(accounts.set_role, s, user_id, body.role)
        return accounts.user_out(s, u)


class ProfileIn(BaseModel):
    full_name: NoNulStr | None = Field(default=None, max_length=100)
    employee_number: NoNulStr | None = Field(default=None, max_length=40)
    email: NoNulStr | None = Field(default=None, max_length=254)  # indirizzo personale (Reply-To e Cc delle mail)
    contest_recipients: list[Annotated[NoNulStr, Field(max_length=254)]] = Field(default_factory=list, max_length=10)


@router.put("/users/{user_id}/profile")
def set_profile(user_id: int, body: ProfileIn, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        u = _call(_user, s, user_id)
        _call(accounts.set_profile, s, u.id, body.full_name, body.employee_number, body.contest_recipients,
              body.email)
        return accounts.user_out(s, u)


@router.post("/users/{user_id}/pairing-code")
def pairing_code(user_id: int, request: Request, device: SystemAdminDep):
    """Codice monouso per associare un telefono a questo utente: lo si inserisce nell'app del telefono nuovo.
    Il codice in chiaro esiste solo in questa risposta."""
    settings = request.app.state.settings
    with _db(request).session() as s:
        u = _call(_user, s, user_id)
        code = services.new_pairing_code(s, u, settings)
        return {"code": code, "valid_minutes": settings.pairing_code_minutes, "username": u.username}


@router.delete("/devices/{device_id}")
def revoke_device(device_id: str, request: Request, device: SystemAdminDep):
    """Revoca un telefono (perso o sostituito): token e chiavi non valgono più, i link che ha creato si revocano."""
    if device_id == device.id:
        raise HTTPException(422, "non puoi revocare il telefono che stai usando")
    with _db(request).session() as s:
        if s.get(Device, device_id) is None:
            raise HTTPException(404, "telefono inesistente")
        d, links = _call(services.revoke_device, s, device_id)
        return {"device_id": d.id, "revoked_at": d.revoked_at.isoformat(), "links_revoked": links}


def _user(s, user_id: int) -> User:
    u = s.get(User, user_id)
    if u is None:
        raise LookupError("utente inesistente")
    return u


# --- impostazioni di tutto il sistema ------------------------------------------------------------------------


class SettingsIn(BaseModel):
    retention_days: int


@router.patch("/settings")
def patch_settings(body: SettingsIn, request: Request, device: SystemAdminDep):
    """Conservazione dei video, uguale per tutti: la cambia solo l'amministratore (la lettura è in
    GET /api/v1/admin/settings)."""
    with _db(request).session() as s:
        _call(services.set_retention_days, s, body.retention_days)
        return {"retention_days": services.get_retention_days(s, request.app.state.settings)}


# --- articolazioni orarie e assegnazioni -------------------------------------------------------------------------


class ScheduleIn(BaseModel):
    name: NoNulStr = Field(max_length=100)
    version: orario.Schedule  # la prima versione


class NameIn(BaseModel):
    name: NoNulStr = Field(max_length=100)


@router.get("/schedules")
def list_schedules(request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        return accounts.list_schedules(s)


@router.post("/schedules")
def create_schedule(body: ScheduleIn, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        return _call(accounts.create_schedule, s, body.name, body.version)


@router.put("/schedules/{schedule_id}/name")
def rename_schedule(schedule_id: int, body: NameIn, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        return _call(accounts.rename_schedule, s, schedule_id, body.name)


@router.put("/schedules/{schedule_id}/versions")
def put_version(schedule_id: int, body: orario.Schedule, request: Request, device: SystemAdminDep):
    """Aggiunge una versione datata (o sostituisce quella con la stessa data): vale per tutti i profili assegnati."""
    with _db(request).session() as s:
        return _call(accounts.put_version, s, schedule_id, body)


@router.delete("/schedules/{schedule_id}/versions/{valid_from}")
def delete_version(schedule_id: int, valid_from: date, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        return _call(accounts.delete_version, s, schedule_id, valid_from)


@router.delete("/schedules/{schedule_id}")
def delete_schedule(schedule_id: int, request: Request, device: SystemAdminDep):
    with _db(request).session() as s:
        _call(accounts.delete_schedule, s, schedule_id)
        return {"deleted": schedule_id}


class AssignmentIn(BaseModel):
    schedule_id: int
    valid_from: date


@router.put("/users/{user_id}/assignments")
def set_assignments(user_id: int, body: list[AssignmentIn], request: Request, device: SystemAdminDep):
    """Articolazioni assegnate all'utente, ognuna dal suo giorno: sostituiscono le precedenti."""
    with _db(request).session() as s:
        _call(_user, s, user_id)
        return _call(accounts.set_assignments, s, user_id, [(a.schedule_id, a.valid_from) for a in body])

"""API di gestione per l'app (storico, flag, video, pacchetti di prova, link, impostazioni).

Tutte richiedono il token del dispositivo e la firma con la chiave admin (biometrica): vedi
admin_auth.py. Nessuna operazione cancella le prove: si possono eliminare solo i file video.
"""

import json
from datetime import date
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from timbro import evidence, mailer, services
from timbro.api import media
from timbro.api.admin_auth import AdminDep
from timbro.db import utcnow
from timbro.validation import NoNulStr, PlausibleDatetime
from timbro.models import ContestNotice, Punch, ShareAccess, ShareLink

router = APIRouter(prefix="/api/v1/admin")


def _db(request: Request):
    return request.app.state.db


def _settings(request: Request):
    return request.app.state.settings


def _punch_summary(p: Punch) -> dict:
    st = services.punch_status(p)
    st.update(
        keep=p.keep,
        contested=p.contested,
        justification_ref=p.justification_ref,
        notes=p.notes,
        device_time=p.device_time,
        video_available=p.video_path is not None and p.video_deleted_at is None,
        video_deleted_at=p.video_deleted_at.isoformat() if p.video_deleted_at else None,
        video_deleted_reason=p.video_deleted_reason,
    )
    return st


def _own(s, device, punch_id: int) -> Punch:
    p = s.get(Punch, punch_id)
    if p is None or p.user_id != device.user_id:
        raise HTTPException(404, "timbratura non trovata")
    return p


# --- storico -----------------------------------------------------------------------------------


@router.get("/punches")
def list_punches(
    request: Request,
    device: AdminDep,
    since: PlausibleDatetime | None = None,
    until: PlausibleDatetime | None = None,
    contested: bool | None = None,
    before_id: int | None = None,
    limit: int = 50,
):
    limit = max(1, min(limit, 200))
    with _db(request).session() as s:
        q = select(Punch).where(Punch.user_id == device.user_id)
        if since:
            q = q.where(Punch.server_received_at >= since)
        if until:
            q = q.where(Punch.server_received_at < until)
        if contested is not None:
            q = q.where(Punch.contested.is_(contested))
        if before_id:
            q = q.where(Punch.id < before_id)
        rows = s.scalars(q.order_by(Punch.id.desc()).limit(limit)).all()
        return {"items": [_punch_summary(p) for p in rows], "next_before_id": rows[-1].id if len(rows) == limit else None}


@router.get("/punches/{punch_id}")
def get_punch(punch_id: int, request: Request, device: AdminDep):
    """Dettaglio: in più l'esito completo dell'attestazione e le reti Wi-Fi con quante altre timbrature le hanno viste."""
    with _db(request).session() as s:
        p = _own(s, device, punch_id)
        return {
            **_punch_summary(p),
            "attestation_detail": json.loads(p.attestation_json) if p.attestation_json else None,
            "wifi": services.wifi_summary(s, p),
            "notices": [
                services.notice_summary(n)
                for n in s.scalars(select(ContestNotice).where(ContestNotice.punch_id == p.id).order_by(ContestNotice.id))
            ],
        }


class PunchPatch(BaseModel):
    keep: bool | None = None
    contested: bool | None = None
    justification_ref: NoNulStr | None = Field(default=None, max_length=255)
    notes: NoNulStr | None = Field(default=None, max_length=4000)


@router.patch("/punches/{punch_id}")
def patch_punch(punch_id: int, body: PunchPatch, request: Request, device: AdminDep):
    with _db(request).session() as s:
        p = _own(s, device, punch_id)
        for field, value in body.model_dump(exclude_unset=True).items():
            if field in ("keep", "contested") and value is None:
                raise HTTPException(422, f"{field} deve essere true o false")
            setattr(p, field, value)
        s.commit()
        return _punch_summary(p)


@router.delete("/punches/{punch_id}/video")
def delete_video(punch_id: int, request: Request, device: AdminDep):
    with _db(request).session() as s:
        p = _own(s, device, punch_id)
        if p.keep or p.contested:
            raise HTTPException(409, "video segnato da tenere o contestato: togli prima il flag")
        if p.video_deleted_at is not None:
            return _punch_summary(p)
        if p.video_received_at is None:
            raise HTTPException(409, "il video non è ancora arrivato al server")
        if not services.delete_video(s, _settings(request), punch_id, "manual"):
            raise HTTPException(409, "flag cambiato nel frattempo: video non cancellato")
        s.expire_all()
        return _punch_summary(s.get(Punch, punch_id))


@router.post("/punches/{punch_id}/video-ticket")
def video_ticket(punch_id: int, request: Request, device: AdminDep):
    """URL temporaneo per il player dell'app (più richieste Range senza firma a ognuna)."""
    with _db(request).session() as s:
        p = _own(s, device, punch_id)
        if p.video_path is None or p.video_deleted_at is not None:
            raise HTTPException(404, "video non disponibile")
    token = request.app.state.tickets.issue(punch_id, device.user_id)
    return {"url": f"/api/v1/media/{token}", "expires_in": media.TTL_S}


@router.get("/punches/{punch_id}/evidence.zip")
async def get_evidence(punch_id: int, request: Request, device: AdminDep):
    return await evidence_response(request, punch_id, device.user_id)


async def evidence_response(request: Request, punch_id: int, user_id: int) -> StreamingResponse:
    """Zip dalla cache (costruito una volta per versione), servito per intero: niente Range."""

    def build():
        with _db(request).session() as s:
            p = s.get(Punch, punch_id)
            if p is None or p.user_id != user_id:
                raise HTTPException(404, "timbratura non trovata")
            return evidence.build(s, punch_id, _settings(request)), evidence.download_name(p)

    path, name = await run_in_threadpool(build)
    f = await run_in_threadpool(open, path, "rb")

    def chunks():
        with f:
            yield from iter(lambda: f.read(1 << 20), b"")

    return StreamingResponse(
        chunks(),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="{name}"',
            "Content-Length": str(path.stat().st_size),
            "Accept-Ranges": "none",
        },
    )


# --- link di condivisione ----------------------------------------------------------------------


class ShareIn(BaseModel):
    punch_ids: list[int] = Field(min_length=1, max_length=500)
    label: NoNulStr = Field(min_length=1, max_length=200)
    days: int | None = None  # se manca: share_default_days (15); al massimo share_max_days (60)
    allow_evidence_download: bool = True


def _share_days(s, shares: list[ShareLink]) -> dict[int, list[str]]:
    """Giornate (ora italiana, ora della timbratura) di ogni link: nell'app un link si riconosce dal giorno."""
    ids = {pid for sh in shares for pid in json.loads(sh.punch_ids_json)}
    day_of = {
        p.id: services.punch_time(p).astimezone(services.LOCAL_TZ).date().isoformat()
        for p in (s.scalars(select(Punch).where(Punch.id.in_(ids))) if ids else [])
    }
    return {sh.id: sorted({day_of[i] for i in json.loads(sh.punch_ids_json) if i in day_of}) for sh in shares}


def _share_summary(sh: ShareLink, accesses: int, days: list[str]) -> dict:
    return {
        "id": sh.id,
        "label": sh.label,
        "punch_ids": json.loads(sh.punch_ids_json),
        "days": days,
        "allow_evidence_download": sh.allow_evidence_download,
        "created_at": sh.created_at.isoformat(),
        "expires_at": sh.expires_at.isoformat(),
        "revoked_at": sh.revoked_at.isoformat() if sh.revoked_at else None,
        "active": sh.revoked_at is None and sh.expires_at > utcnow(),
        "accesses": accesses,
    }


@router.post("/shares")
def create_share(body: ShareIn, request: Request, device: AdminDep):
    settings = _settings(request)
    with _db(request).session() as s:
        try:
            sh, token = services.create_share(
                s, settings, device, body.punch_ids, body.label, settings.share_default_days if body.days is None else body.days,
                body.allow_evidence_download,
            )
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        # il token in chiaro esiste solo in questa risposta: nel DB c'è solo il suo hash
        return {**_share_summary(sh, 0, _share_days(s, [sh])[sh.id]), "url": services.share_url(settings, token)}


@router.get("/shares")
def list_shares(request: Request, device: AdminDep):
    with _db(request).session() as s:
        counts = dict(s.execute(select(ShareAccess.share_id, func.count()).group_by(ShareAccess.share_id)).all())
        rows = s.scalars(
            select(ShareLink).where(ShareLink.user_id == device.user_id).order_by(ShareLink.id.desc())
        ).all()
        days = _share_days(s, rows)
        return [_share_summary(sh, counts.get(sh.id, 0), days[sh.id]) for sh in rows]


def _own_share(s, device, share_id: int) -> ShareLink:
    sh = s.get(ShareLink, share_id)
    if sh is None or sh.user_id != device.user_id:
        raise HTTPException(404, "link non trovato")
    return sh


@router.delete("/shares/{share_id}")
def revoke_share(share_id: int, request: Request, device: AdminDep):
    with _db(request).session() as s:
        sh = _own_share(s, device, share_id)
        if sh.revoked_at is None:
            sh.revoked_at = utcnow()
            s.commit()
        n = s.scalar(select(func.count()).select_from(ShareAccess).where(ShareAccess.share_id == sh.id))
        return _share_summary(sh, n, _share_days(s, [sh])[sh.id])


@router.get("/shares/{share_id}/accesses")
def share_accesses(
    share_id: int, request: Request, device: AdminDep, after_id: int = 0, limit: int = Query(500, ge=1, le=1000)
):
    """Registro degli accessi a pagine, dal più vecchio: per la pagina dopo, after_id = id dell'ultima riga ricevuta."""
    with _db(request).session() as s:
        sh = _own_share(s, device, share_id)
        rows = s.scalars(
            select(ShareAccess)
            .where(ShareAccess.share_id == sh.id, ShareAccess.id > after_id)
            .order_by(ShareAccess.id)
            .limit(limit)
        )
        return [
            {"id": a.id, "at": a.at.isoformat(), "ip": a.ip, "user_agent": a.user_agent, "what": a.what} for a in rows
        ]


# --- impostazioni ------------------------------------------------------------------------------


class SettingsIn(BaseModel):
    retention_days: int


# --- profilo e contestazioni --------------------------------------------------------------------


class ProfileIn(BaseModel):
    full_name: NoNulStr | None = Field(default=None, max_length=100)
    employee_number: NoNulStr | None = Field(default=None, max_length=40)
    email: NoNulStr | None = Field(default=None, max_length=254)  # indirizzo personale (Reply-To e Cc delle mail)
    contest_recipients: list[Annotated[NoNulStr, Field(max_length=254)]] = Field(default_factory=list, max_length=10)


def _profile_out(request: Request, s, user_id: int) -> dict:
    return {**services.get_profile(s, user_id), "mail_configured": mailer.configured(_settings(request))}


@router.get("/profile")
def get_profile(request: Request, device: AdminDep):
    with _db(request).session() as s:
        return _profile_out(request, s, device.user_id)


@router.put("/profile")
def put_profile(body: ProfileIn, request: Request, device: AdminDep):
    with _db(request).session() as s:
        try:
            services.set_profile(s, device.user_id, body.full_name, body.employee_number, body.contest_recipients,
                                  body.email)
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        return _profile_out(request, s, device.user_id)


class ContestIn(BaseModel):
    days: int | None = None
    # None = i destinatari del profilo; [] = nessuna mail, solo il link
    recipients: list[Annotated[NoNulStr, Field(max_length=254)]] | None = Field(default=None, max_length=10)
    note: NoNulStr | None = Field(default=None, max_length=1000)


def _contest(request: Request, device, body: ContestIn, punch_id: int | None, day: date | None) -> dict:
    settings = _settings(request)
    with _db(request).session() as s:
        p = _own(s, device, punch_id) if punch_id is not None else None
        try:
            share, token, notice, hours, schedule = services.contest(
                s, settings, device, p, settings.share_default_days if body.days is None else body.days,
                body.recipients, (body.note or "").strip() or None, day,
            )
        except services.ContestError as e:
            raise HTTPException(409, str(e)) from e
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        out = {
            "day": (day or services.punch_time(p).astimezone(services.LOCAL_TZ).date()).isoformat(),
            "punch": _punch_summary(s.get(Punch, punch_id)) if punch_id is not None else None,
            # il token in chiaro esiste solo in questa risposta (e nella mail)
            "share": {**_share_summary(share, 0, _share_days(s, [share])[share.id]), "url": services.share_url(settings, token)},
            "notice": services.notice_summary(notice) if notice else None,
            "hours": services.hours_summary(hours, schedule),
        }
    if notice is not None and request.app.state.worker:
        request.app.state.worker.wake()
    return out


@router.post("/punches/{punch_id}/contest")
def contest_punch(punch_id: int, body: ContestIn, request: Request, device: AdminDep):
    """Contesta una timbratura: link con tutta la giornata (validità scelta qui) e mail ai destinatari."""
    return _contest(request, device, body, punch_id, None)


@router.get("/notices")
def list_notices(request: Request, device: AdminDep, limit: int = Query(100, ge=1, le=500)):
    """Tutte le mail di contestazione dell'utente, dalla più recente: anche le segnalazioni di una giornata intera,
    che non stanno nel dettaglio di nessuna timbratura."""
    with _db(request).session() as s:
        rows = s.scalars(
            select(ContestNotice).where(ContestNotice.user_id == device.user_id).order_by(ContestNotice.id.desc()).limit(limit)
        ).all()
        return [services.notice_summary(n) for n in rows]


@router.post("/days/{day}/contest")
def contest_day(day: date, body: ContestIn, request: Request, device: AdminDep):
    """Segnala tutta una giornata (più timbrature sbagliate, o ore calcolate male dall'ente): link, ore valide
    secondo l'orario dell'utente e mail."""
    if not 2000 <= day.year <= 2200:
        raise HTTPException(422, "data non valida")
    return _contest(request, device, body, None, day)


@router.get("/settings")
def get_settings(request: Request, device: AdminDep):
    with _db(request).session() as s:
        return {
            "retention_days": services.get_retention_days(s, _settings(request)),
            "share_default_days": _settings(request).share_default_days,
            "share_max_days": _settings(request).share_max_days,
        }

"""API per l'app Android (/api/v1). Autenticazione: token del dispositivo in Authorization: Bearer."""

import base64
import hashlib
import os
import tempfile
import threading
from datetime import date, datetime
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlalchemy.orm import Session

from timbro import services
from timbro.db import utcnow
from timbro.manifest import ManifestError, verify_and_parse
from timbro.validation import NoNulStr
from timbro.models import Device, Punch

router = APIRouter(prefix="/api/v1")


def get_session(request: Request):
    yield from request.app.state.db.dependency()


SessionDep = Annotated[Session, Depends(get_session)]


def get_device(request: Request, authorization: Annotated[str | None, Header()] = None) -> Device:
    # sessione propria e breve: l'oggetto resta leggibile (expire_on_commit=False) e nessuna
    # connessione rimane occupata per tutta la richiesta (es. durante un upload lento)
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "token mancante")
    with request.app.state.db.session() as s:
        device = services.device_by_token(s, authorization.removeprefix("Bearer "))
    if device is None:
        raise HTTPException(401, "token non valido")
    return device


DeviceDep = Annotated[Device, Depends(get_device)]


class RegisterIn(BaseModel):
    pairing_code: NoNulStr = Field(max_length=64)
    name: NoNulStr = Field(max_length=100)
    public_key_pem: NoNulStr = Field(max_length=4000)
    # chiave utilizzabile solo dopo autenticazione biometrica (operazioni di gestione)
    admin_public_key_pem: NoNulStr = Field(max_length=4000)
    attestation_chain_pem: NoNulStr | None = Field(default=None, max_length=40000)


@router.post("/devices/register")
def register(body: RegisterIn, s: SessionDep):
    try:
        device, token = services.register_device(
            s, body.pairing_code, body.name, body.public_key_pem, body.admin_public_key_pem, body.attestation_chain_pem
        )
    except services.PairingError as e:
        raise HTTPException(403, str(e)) from e
    except ManifestError as e:
        raise HTTPException(422, str(e)) from e
    return {"device_id": device.id, "api_token": token}


class EnrollIn(RegisterIn):
    # richiesta di certificato (PKCS#10 PEM) per la chiave TLS creata nel chip sicuro del telefono
    csr_pem: NoNulStr = Field(max_length=8000)


@router.post("/enroll")
def enroll(body: EnrollIn, s: SessionDep, request: Request):
    """Associazione completa: registra il telefono (come /devices/register) e firma il suo certificato client per
    mTLS. È l'unico percorso dell'API che nginx espone senza certificato client (il codice monouso lo protegge)."""
    from timbro import enroll as enrolling

    settings = request.app.state.settings
    try:
        enrolling.load_ca(settings)  # prima di consumare il codice
        csr = enrolling.parse_csr(body.csr_pem)
    except enrolling.NotConfigured as e:
        raise HTTPException(503, str(e)) from e
    except enrolling.EnrollError as e:
        raise HTTPException(422, str(e)) from e
    try:
        device, token = services.register_device(
            s, body.pairing_code, body.name, body.public_key_pem, body.admin_public_key_pem, body.attestation_chain_pem
        )
    except services.PairingError as e:
        raise HTTPException(403, str(e)) from e
    except ManifestError as e:
        raise HTTPException(422, str(e)) from e
    cert_pem, chain_pem, fingerprint = enrolling.issue(csr, device.id, settings)
    s.get(Device, device.id).client_cert_sha256 = fingerprint
    s.commit()
    return {"device_id": device.id, "api_token": token, "client_cert_pem": cert_pem, "client_chain_pem": chain_pem}


class PunchIn(BaseModel):
    manifest: str = Field(description="byte esatti del manifest JSON, in base64", max_length=20000)
    signature: str = Field(description="firma ECDSA DER dei byte del manifest, in base64", max_length=200)
    attestation: list[Annotated[str, Field(max_length=22000)]] | None = Field(
        default=None,
        max_length=10,
        description="catena di Key Attestation di una chiave usa e getta con challenge = SHA-256 del manifest: "
        "certificati DER in base64, dalla foglia alla radice",
    )


@router.post("/punches")
def create_punch(body: PunchIn, s: SessionDep, device: DeviceDep, request: Request):
    try:
        raw = base64.b64decode(body.manifest, validate=True)
        sig = base64.b64decode(body.signature, validate=True)
    except ValueError as e:
        raise HTTPException(422, "base64 non valido") from e
    try:
        m = verify_and_parse(device.public_key_pem, raw, sig)
    except ManifestError as e:
        raise HTTPException(422, str(e)) from e
    if m.device_id != device.id:
        raise HTTPException(422, "device_id del manifest diverso dal dispositivo autenticato")
    settings = request.app.state.settings
    if m.size > settings.max_video_bytes:
        raise HTTPException(413, "video troppo grande")
    try:
        punch, created = services.create_punch(s, device, m, raw, sig, settings, body.attestation)
    except ValueError as e:
        raise HTTPException(409, str(e)) from e
    if created and request.app.state.worker:
        request.app.state.worker.wake()
    return services.punch_status(punch)


def _own_punch(s: Session, device: Device, punch_id: int) -> Punch:
    """Solo le timbrature di questo telefono: il dettaglio di tutto l'utente (con le posizioni) passa dall'API admin,
    che chiede la firma biometrica; /history dà la vista per giorno senza posizioni."""
    p = s.get(Punch, punch_id)
    if p is None or p.user_id != device.user_id or p.device_id != device.id:
        raise HTTPException(404, "timbratura non trovata")
    return p


@router.get("/punches/{punch_id}")
def get_punch(punch_id: int, s: SessionDep, device: DeviceDep):
    return services.punch_status(_own_punch(s, device, punch_id))


@router.get("/history")
def get_history(s: SessionDep, device: DeviceDep, until: date | None = None, days: int = 14):
    """Storico per giorno di tutte le timbrature dell'utente (anche di telefoni precedenti), senza posizioni né reti:
    basta il token, niente impronta. Dettaglio e modifiche restano nell'API admin."""
    today = datetime.now(services.LOCAL_TZ).date()
    if until is not None and until.year < 2000:
        raise HTTPException(422, "data non valida")
    return services.history(s, device.user_id, min(until or today, today), days)


@router.get("/me")
def me(s: SessionDep, device: DeviceDep, request: Request):
    """Chi è l'utente del telefono: ruolo (l'app mostra l'amministrazione solo agli admin), profilo e assegnazioni."""
    from timbro import accounts
    from timbro.models import User

    u = s.get(User, device.user_id)
    from timbro import mailer

    return {
        "user_id": u.id, "username": u.username, "role": u.role, "device_id": device.id,
        "profile": accounts.get_profile(s, u.id), "assignments": accounts.assignments_out(s, u.id),
        "mail_configured": mailer.configured(request.app.state.settings),
    }


@router.get("/schedule")
def get_schedule(s: SessionDep, device: DeviceDep):
    """Articolazione dell'orario dell'utente (versioni datate): all'app serve per le ore valide dello storico. Si
    modifica dall'API admin (PUT /api/v1/admin/schedule)."""
    return services.get_schedules(s, device.user_id).model_dump(mode="json")


@router.put("/punches/{punch_id}/video")
async def upload_video(punch_id: int, request: Request, device: DeviceDep):
    """Riceve il video originale; lo accetta solo se lo SHA-256 coincide con quello del manifest.

    Ogni upload scrive in un file temporaneo proprio (upload concorrenti non si pestano), il file
    viene sincronizzato su disco prima di spostarlo al posto definitivo, e la ricezione si registra
    una sola volta (UPDATE condizionato). Le operazioni bloccanti girano nel threadpool.
    """
    db = request.app.state.db
    settings = request.app.state.settings

    def lookup():
        with db.session() as s:
            p = _own_punch(s, device, punch_id)
            return services.punch_status(p) if p.video_received_at else None, p.size, p.sha256, p.server_received_at

    done, size_expected, sha_expected, received = await run_in_threadpool(lookup)
    if done:
        return done

    rel = f"{received:%Y/%m}/{punch_id}.mp4"
    dest = settings.videos_dir / rel
    await run_in_threadpool(dest.parent.mkdir, parents=True, exist_ok=True)
    fd, tmp_name = await run_in_threadpool(tempfile.mkstemp, dir=dest.parent, prefix=f"{punch_id}.", suffix=".part")
    tmp = Path(tmp_name)
    h = hashlib.sha256()
    size = 0
    try:
        with os.fdopen(fd, "wb") as f:
            async for chunk in request.stream():
                size += len(chunk)
                if size > size_expected:
                    raise HTTPException(422, "video più grande di quanto dichiarato nel manifest")
                h.update(chunk)
                await run_in_threadpool(f.write, chunk)
            await run_in_threadpool(f.flush)
            await run_in_threadpool(os.fsync, f.fileno())
        if size != size_expected or h.hexdigest() != sha_expected:
            raise HTTPException(422, "il video non corrisponde all'impronta del manifest")
        await run_in_threadpool(_commit_video, db, punch_id, tmp, dest, rel, sha_expected)
    finally:
        if tmp.exists():
            tmp.unlink()

    def status():
        with db.session() as s:
            return services.punch_status(s.get(Punch, punch_id))

    return await run_in_threadpool(status)


_video_lock = threading.Lock()


def _commit_video(db, punch_id: int, tmp: Path, dest: Path, rel: str, sha_expected: str) -> None:
    with _video_lock:
        with db.session() as s:
            if s.get(Punch, punch_id).video_received_at is not None:
                return  # un upload concorrente ha già completato: il suo file è quello buono
        os.replace(tmp, dest)
        _fsync_dir(dest.parent)
        # controllo finale sul file al suo posto, non solo sui byte arrivati in rete
        if _sha256_file(dest) != sha_expected:
            dest.unlink()
            raise HTTPException(500, "video corrotto durante il salvataggio")
        with db.session() as s:
            s.execute(
                update(Punch)
                .where(Punch.id == punch_id, Punch.video_received_at.is_(None))
                .values(video_path=rel, video_received_at=utcnow())
            )
            s.commit()


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _fsync_dir(path: Path) -> None:
    if os.name != "posix":
        return  # su Windows le directory non si aprono per fsync
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)

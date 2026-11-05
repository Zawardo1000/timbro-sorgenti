"""Autenticazione delle operazioni di gestione: token del dispositivo + firma con la chiave admin.

La chiave admin sul telefono è utilizzabile solo dopo l'autenticazione biometrica, quindi un
telefono sbloccato ma in mano ad altri non può creare link, cambiare flag o cancellare video.

Il telefono firma (ECDSA P-256 / SHA-256, DER, base64 in X-Timbro-Signature) la stringa:

    TIMBRO-ADMIN-1\\n<METODO>\\n<percorso>[?<query grezza>]\\n<X-Timbro-Time>\\n<X-Timbro-Nonce>\\n<sha256 hex del corpo>

X-Timbro-Time è ISO 8601 con fuso e deve stare entro admin_clock_skew_s dall'ora del server;
X-Timbro-Nonce (16-64 caratteri base64url) non può essere riusato: niente replay.

Percorso e query sono quelli grezzi della richiesta (scope["raw_path"] e scope["query_string"]), cioè esattamente
ciò che il telefono ha mandato e firmato, e ciò da cui FastAPI ricava percorso e parametri: la firma resta legata
alla risorsa e ai parametri usati davvero. Le richieste con "#" (mai valide in una richiesta HTTP) sono rifiutate.
"""

import base64
import hashlib
import re
from datetime import datetime, timedelta
from typing import Annotated

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError

from timbro.api.app_api import DeviceDep
from timbro.db import utcnow
from timbro.manifest import load_public_key
from timbro.models import AdminNonce, Device

NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
PREFIX = "TIMBRO-ADMIN-1"


def canonical(method: str, path: str, query: str, time: str, nonce: str, body: bytes) -> bytes:
    target = f"{path}?{query}" if query else path
    return "\n".join([PREFIX, method.upper(), target, time, nonce, hashlib.sha256(body).hexdigest()]).encode()


async def get_admin_device(request: Request, device: DeviceDep) -> Device:
    settings = request.app.state.settings
    h = request.headers
    time_s, nonce, sig_b64 = h.get("x-timbro-time"), h.get("x-timbro-nonce"), h.get("x-timbro-signature")
    if not (time_s and nonce and sig_b64):
        raise HTTPException(401, "firma admin mancante")
    if device.admin_public_key_pem is None:
        raise HTTPException(403, "questo dispositivo non ha una chiave admin")
    if not NONCE_RE.match(nonce):
        raise HTTPException(401, "nonce non valido")
    try:
        t = datetime.fromisoformat(time_s)
    except ValueError:
        raise HTTPException(401, "ora non valida") from None
    now = utcnow()
    if t.tzinfo is None or abs((t - now).total_seconds()) > settings.admin_clock_skew_s:
        raise HTTPException(401, "ora della richiesta fuori tolleranza")

    raw_path = request.scope.get("raw_path") or request.scope["path"].encode()
    raw_query = request.scope.get("query_string", b"")
    if b"#" in raw_path or b"#" in raw_query:
        raise HTTPException(400, "richiesta non valida")
    body = await request.body()
    msg = canonical(request.method, raw_path.decode("latin-1"), raw_query.decode("latin-1"), time_s, nonce, body)
    try:
        sig = base64.b64decode(sig_b64, validate=True)
        load_public_key(device.admin_public_key_pem).verify(sig, msg, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        raise HTTPException(401, "firma admin non valida") from None

    def remember_nonce():
        with request.app.state.db.session() as s:
            s.execute(delete(AdminNonce).where(AdminNonce.expires_at < now))
            s.add(
                AdminNonce(
                    nonce=nonce, device_id=device.id, expires_at=now + timedelta(seconds=2 * settings.admin_clock_skew_s)
                )
            )
            try:
                s.commit()
            except IntegrityError:
                raise HTTPException(401, "richiesta già usata (replay)") from None

    await run_in_threadpool(remember_nonce)
    return device


AdminDep = Annotated[Device, Depends(get_admin_device)]

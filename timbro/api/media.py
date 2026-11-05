"""Ticket di breve durata per riprodurre un video dall'app.

Un player (ExoPlayer, MediaPlayer) fa più richieste Range ripetendo le stesse intestazioni: con
la firma admin anti-replay la seconda verrebbe rifiutata. L'app chiede quindi, con una richiesta
admin firmata, un ticket legato a una timbratura e valido pochi minuti, e passa al player l'URL
/api/v1/media/<ticket>. I ticket stanno in memoria (un solo processo): un riavvio li invalida.
"""

import secrets
import threading
import time
from dataclasses import dataclass

from fastapi import APIRouter, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse

from timbro.models import Punch

router = APIRouter(prefix="/api/v1/media")
TTL_S = 300


@dataclass(frozen=True)
class _Ticket:
    punch_id: int
    user_id: int
    expires: float


class Tickets:
    def __init__(self):
        self._items: dict[str, _Ticket] = {}
        self._lock = threading.Lock()

    def issue(self, punch_id: int, user_id: int) -> str:
        token = secrets.token_urlsafe(24)
        now = time.monotonic()
        with self._lock:
            self._items = {k: v for k, v in self._items.items() if v.expires > now}
            self._items[token] = _Ticket(punch_id, user_id, now + TTL_S)
        return token

    def get(self, token: str) -> _Ticket | None:
        t = self._items.get(token)
        return t if t and t.expires > time.monotonic() else None


@router.get("/{ticket}")
async def media(ticket: str, request: Request):
    t = request.app.state.tickets.get(ticket)
    if t is None:
        raise HTTPException(404, "ticket inesistente o scaduto")

    def path():
        with request.app.state.db.session() as s:
            p = s.get(Punch, t.punch_id)
            if p is None or p.user_id != t.user_id or p.video_path is None or p.video_deleted_at is not None:
                raise HTTPException(404, "video non disponibile")
            return request.app.state.settings.videos_dir / p.video_path

    return FileResponse(await run_in_threadpool(path), media_type="video/mp4", headers={"Content-Disposition": "inline"})

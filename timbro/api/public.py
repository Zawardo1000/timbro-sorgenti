"""Pagina pubblica di sola lettura per i link di condivisione (/s/<token>).

Nessuna autenticazione oltre al token (256 bit, nel DB solo il suo hash); scadenza e revoca
controllate a ogni richiesta; limite di frequenza per link. Le intestazioni di sicurezza si
applicano a tutte le risposte (anche errori) in security.py.

Registro degli accessi: una riga per link, cosa (pagina, video, pacchetto) e IP ogni ACCESS_WINDOW_S, con un
tetto per link. Le richieste ripetute (player, ricariche, chi prova a gonfiarlo) non lo fanno crescere senza
limite, e un video conta come visto alla prima richiesta, con o senza Range.
"""

import json
import logging
import re
import threading
import time
from collections import deque
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, select

from timbro import services, share_view
from timbro.api.admin_api import evidence_response
from timbro.api.security import client_ip
from timbro.db import utcnow
from timbro.models import Punch, ShareAccess

router = APIRouter(prefix="/s")
templates = Jinja2Templates(directory=Path(__file__).resolve().parent.parent / "templates")
# compilato una volta all'avvio e mai riletto: durante un deploy il codice nuovo è già su disco mentre gira ancora
# il processo vecchio, e un template nuovo con il contesto vecchio darebbe errore
templates.env.auto_reload = False
templates.get_template("share.html")
LOCAL_TZ = ZoneInfo("Europe/Rome")
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")  # secrets.token_urlsafe(32)
ACCESS_WINDOW_S = 15 * 60
MAX_ACCESSES_PER_LINK = 20_000
log = logging.getLogger(__name__)


class RateLimiter:
    """Finestra scorrevole in memoria per chiave (id del link): basta un solo processo."""

    def __init__(self, limit: int, window_s: float):
        self.limit, self.window = limit, window_s
        self._hits: dict[object, deque] = {}
        self._lock = threading.Lock()
        self._pruned = time.monotonic()

    def allow(self, key) -> bool:
        now = time.monotonic()
        with self._lock:
            if now - self._pruned > self.window:  # le chiavi inattive non si accumulano: pulizia a tempo
                self._hits = {k: v for k, v in self._hits.items() if v and v[-1] > now - self.window}
                self._pruned = now
            q = self._hits.setdefault(key, deque())
            while q and q[0] <= now - self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            return True


def fmt(dt: datetime | None) -> str:
    return dt.astimezone(LOCAL_TZ).strftime("%d/%m/%Y %H:%M:%S") if dt else "—"


def _open_share(request: Request, token: str, what: str | None, punch_id: int | None = None, count: bool = True):
    """Valida il token, controlla che la timbratura sia nel link e registra l'accesso (se what).

    count=False per le richieste Range successive del player (spostamenti nel video): non consumano il limite
    del link, che resta per pagina, inizio dei video e pacchetti. Se però da quell'IP non c'è un accesso recente
    allo stesso video, la richiesta conta lo stesso: chi parte da "bytes=1-" non scarica gratis.
    """
    if not TOKEN_RE.match(token):  # niente database né limitatore per token di forma impossibile
        raise HTTPException(404, "link inesistente, scaduto o revocato")
    with request.app.state.db.session() as s:
        sh = services.share_by_token(s, token)
        if sh is None:
            raise HTTPException(404, "link inesistente, scaduto o revocato")
        if not count and what and not _recent_access(s, sh.id, what, client_ip(request)):
            count = True  # una richiesta Range che non parte da 0 conta comunque la prima volta (niente scorciatoie)
        if count and not request.app.state.share_limiter.allow(sh.id):
            raise HTTPException(429, "troppe richieste, riprova fra poco")
        ids = json.loads(sh.punch_ids_json)
        if punch_id is not None and punch_id not in ids:
            raise HTTPException(404, "timbratura non inclusa nel link")
        if what:
            _log_access(s, request, sh.id, what)
        return sh, ids


def _recent_access(s, share_id: int, what: str, ip: str) -> bool:
    since = utcnow() - timedelta(seconds=ACCESS_WINDOW_S)
    return s.scalar(
        select(ShareAccess.id)
        .where(ShareAccess.share_id == share_id, ShareAccess.what == what, ShareAccess.ip == ip, ShareAccess.at > since)
        .limit(1)
    ) is not None


def _log_access(s, request: Request, share_id: int, what: str) -> None:
    ip = client_ip(request)
    if _recent_access(s, share_id, what, ip):
        return
    total = s.scalar(select(func.count()).select_from(ShareAccess).where(ShareAccess.share_id == share_id))
    if total >= MAX_ACCESSES_PER_LINK:
        log.warning("link %s: registro degli accessi pieno (%d righe), accesso non registrato", share_id, total)
        return
    s.add(ShareAccess(share_id=share_id, ip=ip, user_agent=(request.headers.get("user-agent") or "")[:300], what=what))
    s.commit()


FONTS_DIR = Path(__file__).resolve().parent.parent / "static" / "fonts"
FONTS = {f.name for f in FONTS_DIR.glob("*.woff2")}


@router.get("/static/fonts/{name}")
async def font(name: str):
    """Caratteri della pagina (OFL, licenze nella stessa cartella): serviti da qui, niente Google Fonts, così chi
    apre il link non viene tracciato da terzi."""
    if name not in FONTS:
        raise HTTPException(404, "non trovato")
    return FileResponse(FONTS_DIR / name, media_type="font/woff2")


@router.get("/{token}", response_class=HTMLResponse)
async def share_page(token: str, request: Request):
    def load():
        sh, ids = _open_share(request, token, "page")
        with request.app.state.db.session() as s:
            return sh, share_view.build(s, sh, ids, f"/s/{token}", request.app.state.settings.public_sources_url)

    sh, view = await run_in_threadpool(load)
    return templates.TemplateResponse(request, "share.html", {"share": sh, "v": view, "fmt": fmt})


@router.get("/{token}/p/{punch_id}/video")
async def share_video(token: str, punch_id: int, request: Request):
    # il player fa più richieste Range: sono tutte registrate (una riga per IP ogni ACCESS_WINDOW_S), ma solo la
    # prima (senza Range o dall'inizio del file) consuma il limite del link
    rng = (request.headers.get("range") or "").replace(" ", "")
    first = not rng or rng.startswith("bytes=0-")

    def load():
        _open_share(request, token, f"video:{punch_id}", punch_id, count=first)
        with request.app.state.db.session() as s:
            p = s.get(Punch, punch_id)
            if p.video_path is None or p.video_deleted_at is not None:
                raise HTTPException(404, "video non disponibile")
            return request.app.state.settings.videos_dir / p.video_path

    path = await run_in_threadpool(load)
    return FileResponse(path, media_type="video/mp4", headers={"Content-Disposition": "inline"})


@router.get("/{token}/p/{punch_id}/evidence.zip")
async def share_evidence(token: str, punch_id: int, request: Request):
    def check():
        sh, _ = _open_share(request, token, None, punch_id)
        if not sh.allow_evidence_download:
            raise HTTPException(403, "download non consentito per questo link")
        _open_share(request, token, f"evidence:{punch_id}", punch_id, count=False)  # già contata qui sopra
        return sh.user_id

    user_id = await run_in_threadpool(check)
    return await evidence_response(request, punch_id, user_id)

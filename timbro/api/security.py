"""Intestazioni di sicurezza su TUTTE le risposte (anche errori 4xx) e IP del client.

I token nei log li oscura il formatter (log_setup.py); il registro degli accessi di uvicorn è spento.
"""

from starlette.requests import Request
from starlette.types import ASGIApp, Message, Receive, Scope, Send

COMMON = {
    b"x-content-type-options": b"nosniff",
    b"referrer-policy": b"no-referrer",
    b"cache-control": b"no-store",
}
PUBLIC = {
    b"x-robots-tag": b"noindex, nofollow, noarchive",
    b"content-security-policy": (
        b"default-src 'none'; media-src 'self'; font-src 'self'; style-src 'unsafe-inline'; "
        b"base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    ),
}


class SecurityHeadersMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        extra = dict(COMMON)
        if scope["path"].startswith("/s/"):
            extra.update(PUBLIC)
        if scope["path"].startswith("/s/static/"):
            extra[b"cache-control"] = b"public, max-age=604800"  # caratteri: uguali per tutti, niente dati

        async def send_with_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() not in extra]
                message["headers"] = headers + list(extra.items())
            await send(message)

        await self.app(scope, receive, send_with_headers)


def client_ip(request: Request) -> str | None:
    """IP vero del client: dietro nginx è in X-Real-IP (nginx lo sovrascrive sempre).

    Solo per il registro degli accessi: le altre app sulla rete Docker del server arrivano alla porta senza
    passare da nginx e potrebbero scriverci qualsiasi cosa, quindi non va mai usato per autorizzare.
    """
    real = (request.headers.get("x-real-ip") or "").strip()
    if real:
        return real[:64]
    return request.client.host if request.client else None

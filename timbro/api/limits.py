"""Limite sul corpo delle richieste, applicato prima che FastAPI legga o validi alcunché.

Senza questo, un POST JSON enorme verrebbe letto e interpretato per intero anche senza token.
In produzione Caddy applica gli stessi limiti davanti all'app.
"""

import re

from starlette.types import ASGIApp, Message, Receive, Scope, Send

VIDEO_PATH = re.compile(r"^/api/v1/punches/\d+/video$")


class BodyLimitMiddleware:
    def __init__(self, app: ASGIApp, json_limit: int, video_limit: int):
        self.app = app
        self.json_limit = json_limit
        self.video_limit = video_limit

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        limit = self.video_limit if VIDEO_PATH.match(scope["path"]) else self.json_limit
        for name, value in scope["headers"]:
            if name == b"content-length":
                try:
                    too_big = int(value) > limit
                except ValueError:
                    too_big = True
                if too_big:
                    return await _reject(send)

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise _TooLarge()
            return message

        try:
            await self.app(scope, limited_receive, send)
        except _TooLarge:
            await _reject(send)


class _TooLarge(Exception):
    pass


async def _reject(send: Send) -> None:
    await send({"type": "http.response.start", "status": 413, "headers": [(b"content-type", b"application/json")]})
    await send({"type": "http.response.body", "body": b'{"detail":"richiesta troppo grande"}'})

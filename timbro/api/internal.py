"""Percorsi interni: controllo di salute e problemi in corso. nginx non li pubblica.

Sul server ci arrivano Docker (healthcheck), gitdeploy e timbro-controllo da 127.0.0.1:8012, ma anche le altre
app sulla rete Docker: per questo rispondono solo con stati e conteggi, mai con dati delle timbrature.
"""

from fastapi import APIRouter, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import Response

from timbro import problems
from timbro.db import utcnow

router = APIRouter()


@router.get("/health")
async def health() -> dict:
    # l'app accetta richieste solo dopo l'avvio completo (migrazioni, lock, worker): se risponde è pronta
    return {"status": "ok"}


@router.get("/internal/problemi")
async def current_problems(request: Request) -> Response:
    app_state = request.app.state
    worker = app_state.worker
    state = problems.AppState(
        worker_enabled=worker is not None,
        worker_last_loop=worker.last_loop_at if worker else None,
        worker_failing=dict(worker.failing) if worker else None,
        instance_lock_ok=worker.instance_lock_ok if worker else True,
        schema_state=app_state.schema_state,
    )

    def load() -> str:
        with app_state.db.session() as s:
            return problems.as_json(problems.find(s, app_state.settings, utcnow(), state))

    return Response(await run_in_threadpool(load), media_type="application/json")

from contextlib import asynccontextmanager

from fastapi import FastAPI

from timbro import services
from timbro.api import admin_api, app_api, internal, media, public, system_api
from timbro.api.limits import BodyLimitMiddleware
from timbro.api.security import SecurityHeadersMiddleware
from timbro.config import Settings, check_required, get_settings
from timbro.db import Database
from timbro.worker import Worker


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = check_required(settings or get_settings())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        settings.videos_dir.mkdir(parents=True, exist_ok=True)
        db = Database(settings.database_url)
        # un solo processo per database: la catena e il worker non si coordinano fra processi
        # (niente `uvicorn --workers N`, niente due istanze sullo stesso database)
        db.acquire_instance_lock()
        try:
            # "piu-nuovo" dopo un ritorno a una versione precedente: si parte lo stesso e problems.py lo segnala
            app.state.schema_state = db.migrate()
            services.cleanup_partial_uploads(settings)  # upload interrotti da un arresto brusco (un solo processo)
            app.state.settings = settings
            app.state.db = db
            app.state.tickets = media.Tickets()
            app.state.share_limiter = public.RateLimiter(settings.share_rate_limit_per_min, 60)
            app.state.worker = Worker(db, settings) if settings.worker_enabled else None
            if app.state.worker:
                app.state.worker.start()
            yield
        finally:
            if getattr(app.state, "worker", None):
                app.state.worker.stop(timeout=5)
            db.dispose()

    app = FastAPI(title="Timbro", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.include_router(internal.router)
    app.include_router(app_api.router)
    app.include_router(admin_api.router)
    app.include_router(system_api.router)
    app.include_router(public.router)
    app.include_router(media.router)
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(
        BodyLimitMiddleware, json_limit=settings.max_json_body_bytes, video_limit=settings.max_video_bytes
    )
    return app

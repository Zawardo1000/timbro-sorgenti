"""I test usano un Postgres vero (come il server): TEST_DATABASE_URL, impostata da compose.yaml.

Ogni test parte da uno schema vuoto (DROP SCHEMA public CASCADE). Il database di prova si crea da solo la
prima volta. Senza Docker: docker compose run --rm test.
"""

import os

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

from tests.helpers import Phone
from timbro import services
from timbro.app import create_app
from timbro.config import Settings

TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", "")


@pytest.fixture(scope="session")
def _test_database() -> str:
    if not TEST_DATABASE_URL:
        raise RuntimeError("TEST_DATABASE_URL mancante: i test usano Postgres, lanciali con docker compose run --rm test")
    url = make_url(TEST_DATABASE_URL)
    admin = create_engine(url.set(database="postgres"), isolation_level="AUTOCOMMIT")
    with admin.connect() as c:
        if not c.scalar(text("SELECT 1 FROM pg_database WHERE datname = :n"), {"n": url.database}):
            c.execute(text(f'CREATE DATABASE "{url.database}"'))
    admin.dispose()
    return TEST_DATABASE_URL


@pytest.fixture
def database_url(_test_database) -> str:
    """URL di un database vuoto, senza tabelle né migrazioni."""
    engine = create_engine(_test_database, isolation_level="AUTOCOMMIT")
    with engine.connect() as c:
        c.execute(text("DROP SCHEMA public CASCADE"))
        c.execute(text("CREATE SCHEMA public"))
    engine.dispose()
    return _test_database


@pytest.fixture
def app(tmp_path, database_url):
    # attestazione hardware facoltativa qui: la provano i test di test_attestazione.py
    settings = Settings(
        database_url=database_url, data_dir=tmp_path, worker_enabled=False, attestation_required=False, _env_file=None
    )
    with TestClient(create_app(settings)) as client:
        yield client


@pytest.fixture
def phone(app):
    db = app.app.state.db
    with db.session() as s:
        user = services.create_user(s, "io")
        code = services.new_pairing_code(s, user, app.app.state.settings)
    ph = Phone()
    r = app.post("/api/v1/devices/register", json=ph.registration(code))
    assert r.status_code == 200, r.text
    ph.device_id, ph.token = r.json()["device_id"], r.json()["api_token"]
    # il codice è monouso
    r = app.post("/api/v1/devices/register", json=ph.registration(code, "altro"))
    assert r.status_code == 403
    return ph

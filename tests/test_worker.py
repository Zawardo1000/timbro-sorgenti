"""Worker: esito delle marche con TSA simulate (nessuna rete) e marca giornaliera della catena."""

import hashlib
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select

from timbro.config import Settings
from timbro.db import Database, utcnow
from timbro.models import Device, Punch, Stamp, User
from timbro.stamping import tsa
from timbro.worker import Worker, backoff


@pytest.fixture
def env(tmp_path, database_url):
    settings = Settings(
        database_url=database_url, data_dir=tmp_path, tsa_providers=["accv", "freetsa"], ots_enabled=False,
        periodic_checks_enabled=False, _env_file=None,
    )
    db = Database(settings.database_url)
    db.migrate()
    with db.session() as s:
        s.add(User(id=1, username="io"))
        s.flush()
        s.add(Device(id="d", user_id=1, name="t", public_key_pem="-", api_token_hash="h"))
        s.commit()
    w = Worker(db, settings)
    yield db, settings, w
    w.stop()
    db.dispose()


def add_punch(db, settings, when=None) -> int:
    with db.session() as s:
        p = Punch(
            user_id=1, device_id="d", client_id="c" + str(when), kind="in", sha256="ab" * 32, size=1,
            duration_ms=1, device_time="t", manifest_raw=str(when).encode(), device_signature=b"s",
            server_received_at=when or utcnow(), prev_chain_hash="00" * 32 if when is None else hashlib.sha256(str(when).encode()).hexdigest(),
            chain_hash=hashlib.sha256(b"x" + str(when).encode()).hexdigest(),
        )
        s.add(p)
        s.flush()
        for name in settings.tsa_providers:
            s.add(Stamp(target="video", punch_id=p.id, provider=name, kind="rfc3161", qualified=tsa.PROVIDERS[name].qualified, digest=p.sha256))
        s.commit()
        return p.id


def test_successo_e_ritentativi(env, monkeypatch):
    db, settings, w = env
    add_punch(db, settings)
    gen = datetime(2026, 9, 24, 8, 0, tzinfo=ZoneInfo("UTC"))

    def fake_request(provider, req, client):
        if provider.name == "freetsa":
            raise tsa.StampError("HTTP 503")
        return b"tsr-" + provider.name.encode()

    monkeypatch.setattr(tsa, "request_stamp", fake_request)
    monkeypatch.setattr(
        tsa, "verify_response", lambda p, r, d, n, **kw: tsa.StampInfo(gen, 500, "1.2.3", 42, "CN=Test", True)
    )
    w.run_once(wait=True)
    with db.session() as s:
        st = {x.provider: x for x in s.scalars(select(Stamp))}
    assert st["accv"].status == "complete" and st["accv"].token == b"tsr-accv"
    assert st["accv"].gen_time == gen and st["accv"].accuracy_ms == 500
    assert st["freetsa"].status == "pending" and st["freetsa"].attempts == 1
    assert "503" in st["freetsa"].last_error
    assert st["freetsa"].next_attempt_at > utcnow()

    # un errore temporaneo non rende mai definitiva la marca: si riprova, dopo due giorni ogni 6 ore
    for _ in range(3):
        w.run_once(now=utcnow() + timedelta(hours=2), wait=True)
    with db.session() as s:
        st = s.scalar(select(Stamp).where(Stamp.provider == "freetsa"))
    assert st.status == "pending" and st.attempts == 4
    assert backoff(40, timedelta(days=1)) == timedelta(hours=1)
    assert backoff(40, timedelta(days=3)) == timedelta(hours=6)


def test_marca_giornaliera_catena(env):
    db, settings, w = env
    rome = ZoneInfo("Europe/Rome")
    add_punch(db, settings, when=datetime(2026, 9, 23, 18, 0, tzinfo=rome))
    # 00:05: ancora nel periodo di attesa dopo mezzanotte, niente marca
    w.schedule_daily_chain_stamps(datetime(2026, 9, 24, 0, 5, tzinfo=rome))
    with db.session() as s:
        assert not s.scalars(select(Stamp).where(Stamp.target == "chain")).all()
    now = datetime(2026, 9, 24, 9, 0, tzinfo=rome)
    w.schedule_daily_chain_stamps(now)
    w.schedule_daily_chain_stamps(now)  # idempotente
    with db.session() as s:
        daily = s.scalars(select(Stamp).where(Stamp.target == "chain")).all()
        head = s.scalar(select(Punch))
    assert [(d.chain_day.isoformat(), d.provider, d.qualified) for d in daily] == [("2026-09-23", "accv", True)]
    assert daily[0].digest == head.chain_hash


def test_marca_giornaliera_recupero_e_tutte_le_qualificate(env):
    db, settings, w = env
    settings.tsa_providers = ["freetsa", "accv", "sectigo-q"]  # la prima non è qualificata: va saltata
    rome = ZoneInfo("Europe/Rome")
    add_punch(db, settings, when=datetime(2026, 9, 20, 18, 0, tzinfo=rome))
    # il servizio riparte dopo giorni: si recuperano 20, 21, 22 e 23, ognuno con tutte le TSA qualificate insieme
    # (se una non risponde, l'altra copre il giorno subito)
    w.schedule_daily_chain_stamps(datetime(2026, 9, 24, 9, 0, tzinfo=rome))
    w.schedule_daily_chain_stamps(datetime(2026, 9, 24, 9, 5, tzinfo=rome))  # idempotente
    with db.session() as s:
        rows = s.execute(select(Stamp.chain_day, Stamp.provider).where(Stamp.target == "chain")).all()
    assert sorted({d.day for d, _ in rows}) == [20, 21, 22, 23]
    assert sorted(rows) == sorted((date(2026, 9, d), p) for d in (20, 21, 22, 23) for p in ("accv", "sectigo-q"))


def test_giorno_indipendente_dal_fuso_del_server(env):
    # il server (Oracle) può avere fuso UTC: conta solo l'ora italiana.
    # 22:30 UTC del 23/09 = 00:30 del 24/09 in Italia (ora legale): il 23 è concluso.
    db, settings, w = env
    rome = ZoneInfo("Europe/Rome")
    add_punch(db, settings, when=datetime(2026, 9, 23, 23, 50, tzinfo=rome))  # 21:50 UTC
    w.schedule_daily_chain_stamps(datetime(2026, 9, 23, 22, 30, tzinfo=ZoneInfo("UTC")))
    with db.session() as s:
        assert [d.chain_day for d in s.scalars(select(Stamp).where(Stamp.target == "chain"))] == [date(2026, 9, 23)]


def test_rifiuto_permanente_non_ritentato(env, monkeypatch):
    db, settings, w = env
    add_punch(db, settings)
    monkeypatch.setattr(tsa, "request_stamp", lambda p, r, c: b"x")

    def reject(p, r, d, n, **kw):
        raise tsa.StampError("stato TSA: rejection ['unaccepted_policy']", permanent=True)

    monkeypatch.setattr(tsa, "verify_response", reject)
    w.run_once(wait=True)
    with db.session() as s:
        assert {x.status for x in s.scalars(select(Stamp))} == {"failed"}

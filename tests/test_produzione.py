"""Parti per il server: /health, problemi per gli avvisi Telegram, controlli periodici, IP reale, un solo processo."""

import os
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from tests.helpers import body
from tests.test_admin import share
from timbro import checks, problems, services
from timbro.app import create_app
from timbro.config import Settings
from timbro.db import InstanceLockError, utcnow
from timbro.models import Punch, ShareLink, Stamp
from timbro.stamping.trusted_list import _archive
from timbro.worker import Worker

LOCAL_TZ = ZoneInfo("Europe/Rome")


def new_punch(app, phone, with_video=True) -> int:
    video = os.urandom(2000)
    raw, sig = phone.punch(video)
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    if with_video:
        assert app.put(f"/api/v1/punches/{pid}/video", content=video, headers=phone.auth).status_code == 200
    return pid


def find(app, now, **kw) -> dict[str, str]:
    """Problemi in corso, senza l'avviso del telefono appena associato dalla fixture (lo prova test_avviso_nuovo_telefono)."""
    kw.setdefault("worker_enabled", False)
    with app.app.state.db.session() as s:
        return {p.key: p.text for p in problems.find(s, app.app.state.settings, now, problems.AppState(**kw))
                if not p.key.startswith("telefono-nuovo:")}


def day_of(app, pid) -> str:
    return received_at(app, pid).astimezone(LOCAL_TZ).date().isoformat()


def complete(app, pid, target, provider):
    with app.app.state.db.session() as s:
        s.execute(
            update(Stamp)
            .where(Stamp.punch_id == pid, Stamp.target == target, Stamp.provider == provider)
            .values(status="complete", completed_at=utcnow(), attempts=1)
        )
        s.commit()


def received_at(app, pid) -> datetime:
    with app.app.state.db.session() as s:
        return s.get(Punch, pid).server_received_at


def test_health(app):
    r = app.get("/health")
    assert r.status_code == 200 and r.json() == {"status": "ok"}


def test_problemi_endpoint_vuoto(app):
    r = app.get("/internal/problemi")
    assert r.status_code == 200 and r.json() == {"problemi": []}


def test_marche_mancanti_e_poi_risolte(app, phone):
    pid = new_punch(app, phone)
    t = received_at(app, pid)
    assert find(app, t + timedelta(minutes=5)) == {}  # appena arrivata: le marche hanno tempo
    late = find(app, t + timedelta(hours=1))
    d = day_of(app, pid)
    assert set(late) == {f"marca-qualificata:{d}", f"marca-record:{d}"} and f"#{pid}" in late[f"marca-qualificata:{d}"]
    complete(app, pid, "video", "sectigo-q")  # basta una qualificata
    complete(app, pid, "record", "accv")
    assert find(app, t + timedelta(hours=1)) == {}


def test_tsa_che_fallisce(app, phone):
    pid = new_punch(app, phone)
    with app.app.state.db.session() as s:
        s.execute(
            update(Stamp)
            .where(Stamp.punch_id == pid, Stamp.provider == "accv")
            .values(status="failed", attempts=1, last_error="HTTP 503")
        )
        s.commit()
    found = find(app, received_at(app, pid) + timedelta(minutes=1))
    assert "tsa:accv" in found and "HTTP 503" in found["tsa:accv"] and "qualificata" in found["tsa:accv"]
    pid2 = new_punch(app, phone)  # una marca riuscita dopo chiude il problema
    complete(app, pid2, "video", "accv")
    assert "tsa:accv" not in find(app, received_at(app, pid2) + timedelta(minutes=1))


def test_video_mai_caricato(app, phone):
    pid = new_punch(app, phone, with_video=False)
    t = received_at(app, pid)
    key = f"video-mancanti:{day_of(app, pid)}"
    assert key not in find(app, t + timedelta(hours=11))
    assert f"#{pid}" in find(app, t + timedelta(hours=13))[key]


def test_marca_giornaliera_della_catena(app, phone):
    pid = new_punch(app, phone)
    day = received_at(app, pid).astimezone(LOCAL_TZ).date()
    early = datetime.combine(day + timedelta(days=1), time(1, 0), LOCAL_TZ)
    late = datetime.combine(day + timedelta(days=1), time(3, 0), LOCAL_TZ)
    key = f"catena:{day.isoformat()}"
    assert key not in find(app, early)  # prima delle 02:00 c'è ancora tempo
    assert day.strftime("%d/%m/%Y") in find(app, late)[key]
    # un giorno mancato resta aperto anche settimane dopo (niente "risolto" solo perché è vecchio)
    assert key in find(app, late + timedelta(days=30))
    with app.app.state.db.session() as s:
        head = s.get(Punch, pid).chain_hash
        s.add(Stamp(target="chain", chain_day=day, provider="accv", kind="rfc3161", qualified=True, digest=head,
                    status="complete", completed_at=utcnow()))
        s.commit()
    assert key not in find(app, late)


def test_ots_non_confermata(app, phone):
    pid = new_punch(app, phone)
    t = received_at(app, pid)
    key = f"ots:{day_of(app, pid)}"
    assert key not in find(app, t + timedelta(hours=70))
    assert key in find(app, t + timedelta(hours=73))


def test_esiti_dei_controlli(app):
    now = utcnow()
    with app.app.state.db.session() as s:
        services.set_json_setting(s, checks.ARCHIVE_KEY, {"at": now.isoformat(), "ok": False, "errors": ["catena rotta"],
                                                          "last_success": now.isoformat()})
        services.set_json_setting(s, checks.TL_KEY, {"at": now.isoformat(), "last_success": now.isoformat(),
                                                     "not_granted": ["accv"], "error": None})
    found = find(app, now)
    assert "catena rotta" in found["archivio"] and "accv" in found["trusted-list"]
    # controllo della TL che non riesce: segnalato solo dopo una settimana e due giorni senza successi
    old = (now - timedelta(days=10)).isoformat()
    with app.app.state.db.session() as s:
        services.set_json_setting(s, checks.TL_KEY, {"at": now.isoformat(), "last_success": old, "error": "timeout"})
        services.set_json_setting(s, checks.ARCHIVE_KEY, {"at": now.isoformat(), "ok": True, "errors": [],
                                                          "last_success": now.isoformat()})
    assert set(find(app, now)) == {"trusted-list-controllo"}
    # verifica dell'archivio: non riuscita, mai conclusa, o troppo vecchia
    with app.app.state.db.session() as s:
        services.set_json_setting(s, checks.ARCHIVE_KEY, {"at": now.isoformat(), "error": "OperationalError: x"})
    assert "non riesce" in find(app, now)["archivio-controllo"]
    started = now - timedelta(hours=3)
    with app.app.state.db.session() as s:
        services.set_json_setting(s, checks.ARCHIVE_KEY, {"at": (now - timedelta(days=1)).isoformat(),
                                                          "started_at": started.isoformat(), "ok": True,
                                                          "last_success": (now - timedelta(days=1)).isoformat()})
    assert "non si è conclusa" in find(app, now)["archivio-controllo"]
    with app.app.state.db.session() as s:
        services.set_json_setting(s, checks.ARCHIVE_KEY, {"at": (now - timedelta(days=2)).isoformat(), "ok": True,
                                                          "last_success": (now - timedelta(days=2)).isoformat()})
    assert "non viene verificato" in find(app, now)["archivio-controllo"]


def test_worker_fermo(app):
    now = utcnow()
    assert "worker" in find(app, now, worker_enabled=True, worker_last_loop=now - timedelta(minutes=11))
    assert "worker" not in find(app, now, worker_enabled=True, worker_last_loop=now - timedelta(minutes=1))


def test_controlli_periodici(app, monkeypatch):
    db = app.app.state.db
    settings = app.app.state.settings.model_copy(update={"periodic_checks_enabled": True})
    calls = []

    def fake_tl(db_, settings_):
        calls.append("tl")
        with db.session() as s:
            services.set_json_setting(s, checks.TL_KEY, {"at": clock["now"].isoformat(), "error": None})

    def fake_archive(db_, settings_, today=None):
        calls.append("archive")
        record = {"at": clock["now"].isoformat(), "ok": True, "errors": [], "error": clock.get("error")}
        with db.session() as s:
            services.set_json_setting(s, checks.ARCHIVE_KEY, record)
        return record

    monkeypatch.setattr(checks, "run_trusted_list", fake_tl)
    monkeypatch.setattr(checks, "run_archive", fake_archive)
    w = Worker(db, settings)

    def run(now):
        clock["now"] = now
        w.periodic_checks(now)
        w._check_pool.submit(lambda: None).result()  # aspetta i controlli accodati (un thread solo)

    clock = {}
    day = datetime(2026, 11, 5, tzinfo=LOCAL_TZ)
    run(datetime.combine(day, time(2, 0), LOCAL_TZ))  # prima delle 3: solo la Trusted List (mai fatta)
    assert calls == ["tl"]
    run(datetime.combine(day, time(3, 30), LOCAL_TZ))  # dopo le 3: l'archivio, una volta al giorno
    run(datetime.combine(day, time(4, 0), LOCAL_TZ))
    assert calls == ["tl", "archive"]
    run(datetime.combine(day + timedelta(days=8), time(4, 0), LOCAL_TZ))  # settimana dopo: tutti e due
    assert calls == ["tl", "archive", "tl", "archive"]
    # verifica non riuscita: si riprova dopo un'ora, non al giro dopo
    clock["error"] = "OperationalError: x"
    run(datetime.combine(day + timedelta(days=9), time(4, 0), LOCAL_TZ))
    run(datetime.combine(day + timedelta(days=9), time(4, 30), LOCAL_TZ))
    assert calls.count("archive") == 3
    run(datetime.combine(day + timedelta(days=9), time(5, 1), LOCAL_TZ))
    assert calls.count("archive") == 4
    w.stop()


def test_trusted_list_archiviata_una_volta_per_versione(tmp_path):
    xml = open(os.path.join(os.path.dirname(__file__), "fixtures", "tl", "es_min.xml"), "rb").read()
    a = _archive(tmp_path, "ES", xml)
    b = _archive(tmp_path, "ES", xml)
    assert a == b and len(list(tmp_path.iterdir())) == 1


def test_ip_reale_da_nginx(app, phone):
    pid = new_punch(app, phone)
    sh = share(app, phone, [pid])
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    assert app.get(path, headers={"X-Real-IP": "203.0.113.7"}).status_code == 200
    acc = phone.admin(app, "GET", f"/api/v1/admin/shares/{sh['id']}/accesses").json()
    assert [a["ip"] for a in acc] == ["203.0.113.7"]


def test_un_solo_processo_per_database(app, tmp_path):
    # "app" è già avviata sullo stesso database: una seconda istanza non deve partire
    other = Settings(database_url=app.app.state.settings.database_url, data_dir=tmp_path / "altra",
                     worker_enabled=False, _env_file=None)
    with pytest.raises(InstanceLockError):
        with TestClient(create_app(other)):
            pass


def test_database_url_obbligatoria(monkeypatch):
    from timbro.config import check_required

    with pytest.raises(RuntimeError, match="DATABASE_URL"):
        check_required(Settings(database_url="", _env_file=None))


def test_catena_in_ordine_con_richieste_parallele(app, phone):
    from concurrent.futures import ThreadPoolExecutor

    from timbro import chain

    def one(_):
        video = os.urandom(500)
        raw, sig = phone.punch(video)
        return app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).status_code

    with ThreadPoolExecutor(8) as pool:
        assert set(pool.map(one, range(16))) == {200}
    with app.app.state.db.session() as s:
        rows = s.scalars(select(Punch).order_by(Punch.id)).all()
    assert len(rows) == 16 and rows[0].prev_chain_hash == chain.GENESIS
    for prev, cur in zip(rows, rows[1:]):
        assert cur.prev_chain_hash == prev.chain_hash  # nessuna biforcazione né buco


def test_link_pubblici_15_giorni_se_non_indicato(app, phone):
    pid = new_punch(app, phone)
    sh = share(app, phone, [pid])  # senza "days": la validità predefinita
    expires = datetime.fromisoformat(sh["expires_at"])
    assert timedelta(days=14, hours=23) < expires - utcnow() <= timedelta(days=15)
    other = share(app, phone, [pid], days=40)  # la sceglie l'app, fino a 60 giorni
    assert timedelta(days=39, hours=23) < datetime.fromisoformat(other["expires_at"]) - utcnow() <= timedelta(days=40)
    assert "/s/" in sh["url"] and len(sh["url"].rsplit("/s/", 1)[1]) >= 43  # token di 256 bit nell'URL
    with app.app.state.db.session() as s:
        s.execute(update(ShareLink).values(expires_at=utcnow() - timedelta(seconds=1)))
        s.commit()
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    assert app.get(path).status_code == 404  # scaduto: il token non vale più


def test_avviso_nuovo_telefono(app, phone):
    """Ogni associazione genera un avviso per 24 ore: un codice intercettato e usato da altri si vede subito."""
    from datetime import UTC, datetime, timedelta

    from timbro import problems as pr

    with app.app.state.db.session() as s:
        found = [p for p in pr.find(s, app.app.state.settings, datetime.now(UTC), pr.AppState(worker_enabled=False))
                 if p.key.startswith("telefono-nuovo:")]
        assert len(found) == 1 and phone.device_id[:8] in found[0].text and "@" not in found[0].text
        later = datetime.now(UTC) + timedelta(hours=25)
        assert not [p for p in pr.find(s, app.app.state.settings, later, pr.AppState(worker_enabled=False))
                    if p.key.startswith("telefono-nuovo:")]


def test_avviso_ora_del_telefono_smentita(app, phone):
    """Orologio del telefono spostato prima della ripresa: l'orologio di rete nel manifest lo smentisce."""
    now = datetime.now(LOCAL_TZ)
    video = os.urandom(1000)
    raw, sig = phone.punch(video, device_time=(now - timedelta(minutes=20)).isoformat(timespec="milliseconds"),
                           clocks={"network": now.isoformat(timespec="milliseconds")})
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    found = find(app, received_at(app, pid) + timedelta(minutes=1))
    key = f"ora-telefono:{day_of(app, pid)}"
    assert key in found and f"#{pid}" in found[key] and "20 min dall'orologio di rete" in found[key]
    assert not [k for k in find(app, received_at(app, pid) + timedelta(days=40)) if k.startswith("ora-telefono:")]

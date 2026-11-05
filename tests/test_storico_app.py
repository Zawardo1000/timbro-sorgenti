"""Storico per l'app (GET /api/v1/history): per giorno in ora italiana, con il solo token del telefono."""

import os
from datetime import datetime

from sqlalchemy import update

from tests.helpers import body
from timbro import services
from timbro.models import Punch


def add(app, phone, kind, device_time, received=None, video=True):
    v = os.urandom(1500)
    raw, sig = phone.punch(v, kind=kind, device_time=device_time)
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    if video:
        assert app.put(f"/api/v1/punches/{pid}/video", content=v, headers=phone.auth).status_code == 200
    with app.app.state.db.session() as s:
        s.execute(update(Punch).where(Punch.id == pid).values(
            server_received_at=datetime.fromisoformat(received or device_time)))
        s.commit()
    return pid


def get(app, phone, **params):
    r = app.get("/api/v1/history", params=params, headers=phone.auth)
    assert r.status_code == 200, r.text
    return r.json()


def test_giornata_con_due_entrate_e_due_uscite(app, phone):
    ids = [
        add(app, phone, "in", "2026-11-04T07:58:12+01:00"),
        add(app, phone, "out", "2026-11-04T12:31:00+01:00"),
        add(app, phone, "in", "2026-11-04T13:00:05+01:00", video=False),
        add(app, phone, "out", "2026-11-04T17:02:40+01:00"),
    ]
    with app.app.state.db.session() as s:
        s.execute(update(Punch).where(Punch.id == ids[0]).values(contested=True, keep=True))
        s.commit()
    h = get(app, phone, until="2026-11-05", days=7)
    assert [d["date"] for d in h["days"]] == ["2026-11-04"]
    items = h["days"][0]["punches"]
    assert [p["id"] for p in items] == ids and [p["kind"] for p in items] == ["in", "out", "in", "out"]
    assert items[0]["time"] == "2026-11-04T07:58:12+01:00"
    assert items[0]["contested"] and items[0]["keep"] and not items[1]["contested"]
    assert [p["video"] for p in items] == ["ricevuto", "ricevuto", "mancante", "ricevuto"]
    assert items[0]["attestation_ok"] is None and items[0]["qualified_stamp"] is False
    # niente posizioni, reti o note: restano dietro l'impronta
    text = str(h)
    assert "lat" not in text and "location" not in text and "wifi" not in text and "notes" not in text
    assert h["next_until"] is None


def test_mezzanotte_italiana_e_timbrature_arrivate_dalla_coda(app, phone):
    late = add(app, phone, "out", "2026-11-03T23:30:00+00:00")  # 00:30 del 4 in Italia
    queued = add(app, phone, "out", "2026-11-02T17:00:00+01:00", received="2026-11-03T08:00:00+00:00")
    wrong_clock = add(app, phone, "in", "2026-09-24T07:58:12+02:00", received="2026-11-03T07:00:00+00:00")
    days = {d["date"]: [p["id"] for p in d["punches"]] for d in get(app, phone, until="2026-11-05", days=7)["days"]}
    assert days["2026-11-04"] == [late]
    assert days["2026-11-02"] == [queued]  # il giorno della timbratura, non quello dell'arrivo
    assert days["2026-11-03"] == [wrong_clock]  # ora del telefono non plausibile: conta la ricezione


def test_giorni_completi_e_paginazione(app, phone):
    for day in ("2026-10-20", "2026-10-28", "2026-11-03", "2026-11-04"):
        add(app, phone, "in", f"{day}T08:00:00+01:00")
        add(app, phone, "out", f"{day}T17:00:00+01:00")
    h = get(app, phone, until="2026-11-04", days=2)
    assert [d["date"] for d in h["days"]] == ["2026-11-04", "2026-11-03"]
    assert all(len(d["punches"]) == 2 for d in h["days"])
    assert h["next_until"] == "2026-11-02"
    h = get(app, phone, until=h["next_until"], days=7)  # settimana senza timbrature dopo il 28: si continua
    assert [d["date"] for d in h["days"]] == ["2026-10-28"] and h["next_until"] == "2026-10-26"
    h = get(app, phone, until=h["next_until"], days=62)
    assert [d["date"] for d in h["days"]] == ["2026-10-20"] and h["next_until"] is None


def test_solo_le_timbrature_dell_utente(app, phone):
    mine = add(app, phone, "in", "2026-11-04T08:00:00+01:00")
    other = add(app, phone, "out", "2026-11-04T17:00:00+01:00")
    with app.app.state.db.session() as s:
        someone = services.create_user(s, "qualcun altro")
        s.execute(update(Punch).where(Punch.id == other).values(user_id=someone.id))
        s.commit()
    ids = [p["id"] for d in get(app, phone, until="2026-11-05")["days"] for p in d["punches"]]
    assert ids == [mine]


def test_richieste_non_valide(app, phone):
    assert app.get("/api/v1/history").status_code == 401
    assert app.get("/api/v1/history", params={"until": "0001-01-01"}, headers=phone.auth).status_code == 422
    assert app.get("/api/v1/history", params={"until": "ieri"}, headers=phone.auth).status_code == 422
    assert get(app, phone, until="2026-11-05", days=100000)["days"] == []  # al massimo 62 giorni
    assert get(app, phone, until="2099-01-01")["days"] == []  # mai oltre oggi

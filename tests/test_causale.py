"""Causale delle timbrature: uscita di servizio o per visita medica, e il rientro che la chiude.

Il tempo fuori conta come presenza. La causale non è nel manifest firmato: arriva accanto (POST /punches), si legge
nello storico e si cambia dopo con il PATCH admin, come le note.
"""

import json
import os
from datetime import datetime

from sqlalchemy import update

from tests.helpers import body, give_schedule
from timbro.models import Punch

SCHEDULE = {"versions": [{"valid_from": "2026-01-01", "days": {
    "1": {"due_min": 480}, "2": {"due_min": 480}, "3": {"due_min": 480}, "4": {"due_min": 480},
    "5": {"due_min": 240, "pause": False}}}]}


def add(app, phone, kind, device_time, reason=None, client_id=None):
    raw, sig = phone.punch(os.urandom(1500), kind=kind, device_time=device_time, client_id=client_id)
    r = app.post("/api/v1/punches", json={**body(raw, sig), **({"reason": reason} if reason else {})}, headers=phone.auth)
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    with app.app.state.db.session() as s:
        s.execute(update(Punch).where(Punch.id == pid).values(server_received_at=datetime.fromisoformat(device_time)))
        s.commit()
    return pid


def history(app, phone):
    return app.get("/api/v1/history", params={"until": "2026-11-05", "days": 7}, headers=phone.auth).json()


def test_causale_arriva_con_la_timbratura_ma_fuori_dal_manifest(app, phone):
    out = add(app, phone, "out", "2026-11-04T10:00:00+01:00", "service")
    back = add(app, phone, "in", "2026-11-04T11:00:00+01:00", "service")
    visit = add(app, phone, "out", "2026-11-04T15:00:00+01:00", "medical")
    plain = add(app, phone, "in", "2026-11-04T16:00:00+01:00")
    items = history(app, phone)["days"][0]["punches"]
    assert [(p["id"], p["reason"]) for p in items] == [(out, "service"), (back, "service"), (visit, "medical"), (plain, None)]
    with app.app.state.db.session() as s:
        raw = s.get(Punch, out).manifest_raw
    assert "reason" not in json.loads(raw)  # i byte firmati restano quelli del telefono


def test_causale_non_valida_e_nuovo_invio(app, phone):
    raw, sig = phone.punch(os.urandom(1500), kind="out")
    assert app.post("/api/v1/punches", json={**body(raw, sig), "reason": "ferie"}, headers=phone.auth).status_code == 422
    first = app.post("/api/v1/punches", json={**body(raw, sig), "reason": "service"}, headers=phone.auth).json()
    # lo stesso invio ripetuto (risposta persa) non cambia la causale registrata
    again = app.post("/api/v1/punches", json={**body(raw, sig), "reason": "medical"}, headers=phone.auth).json()
    assert again["id"] == first["id"]
    with app.app.state.db.session() as s:
        assert s.get(Punch, first["id"]).reason == "service"


def test_causale_si_cambia_dopo_con_l_impronta(app, phone):
    pid = add(app, phone, "out", "2026-11-04T10:00:00+01:00")
    url = f"/api/v1/admin/punches/{pid}"
    assert app.patch(url, json={"reason": "service"}, headers=phone.auth).status_code == 401  # serve la firma admin
    r = phone.admin(app, "PATCH", url, {"reason": "medical"})
    assert r.status_code == 200 and r.json()["reason"] == "medical"
    assert phone.admin(app, "PATCH", url, {"notes": "riunione"}).json()["reason"] == "medical"  # le altre modifiche la lasciano
    assert phone.admin(app, "PATCH", url, {"reason": ""}).json()["reason"] is None  # "" la toglie
    assert phone.admin(app, "PATCH", url, {"reason": "service"}).json()["reason"] == "service"
    assert phone.admin(app, "PATCH", url, {"reason": None}).json()["reason"] is None  # anche null
    assert phone.admin(app, "PATCH", url, {"reason": "ferie"}).status_code == 422
    assert phone.admin(app, "GET", url).json()["reason"] is None


def test_ore_e_pagina_per_l_ente_con_il_tempo_fuori(app, phone):
    give_schedule(app, phone, SCHEDULE)
    ids = [
        add(app, phone, "in", "2026-11-04T07:58:00+01:00"),
        add(app, phone, "out", "2026-11-04T10:00:00+01:00", "service"),
        add(app, phone, "in", "2026-11-04T11:00:00+01:00", "service"),
        add(app, phone, "out", "2026-11-04T12:31:00+01:00"),
        add(app, phone, "in", "2026-11-04T13:00:00+01:00"),
        add(app, phone, "out", "2026-11-04T16:30:00+01:00"),
    ]
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": ids, "label": "Giornata"})
    assert r.status_code == 200, r.text
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    # 423 fra le timbrature + 60 fuori per servizio - 1 di pausa contata 30 = 482 (stesso caso di orario.json)
    assert "8 h 02" in page and "+2′" in page
    assert "fuori per servizio dalle 10:00: 60′ contati come presenza" in page
    assert "Uscita di servizio delle 10:00" in page and "Entrata di servizio delle 11:00" in page
    assert "servizio 60′" in page and "pausa 29′" in page and 'class="fuori"' in page
    assert page.count("non fa parte della timbratura firmata") == 2  # solo le due con la causale
    # tolta la causale dall'uscita resta quella del rientro: conta ancora, ma è da verificare
    phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[1]}", {"reason": ""})
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert "8 h 02" in page and "causale diversa fra l&#39;uscita delle 10:00 e il rientro delle 11:00" in page
    # senza nessuna causale l'ora fuori non conta più
    phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[2]}", {"reason": ""})
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert "7 h 02" in page and "uscita delle 10:00 (non è la pausa)" in page


def test_la_causale_non_tocca_la_catena(app, phone):
    """Cambiare la causale non cambia le prove: la catena dipende solo da manifest, firma e ricezione."""
    pid = add(app, phone, "out", "2026-11-04T10:00:00+01:00", "service")
    with app.app.state.db.session() as s:
        before = s.get(Punch, pid).chain_hash
    phone.admin(app, "PATCH", f"/api/v1/admin/punches/{pid}", {"reason": "medical"})
    with app.app.state.db.session() as s:
        assert s.get(Punch, pid).chain_hash == before

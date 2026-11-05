"""Amministrazione: ruoli, utenti e telefoni, articolazioni orarie condivise, assegnazioni datate, migrazione."""

import json
from datetime import date

from alembic import command
from sqlalchemy import select, text

from tests.helpers import Phone, give_schedule
from tests.test_migrations import cfg_for
from timbro import accounts, orario
from timbro.db import Database
from timbro.models import Device, User

V1 = {"valid_from": "2026-01-01", "days": {"1": {"due_min": 480}, "2": {"due_min": 480}, "3": {"due_min": 480},
                                         "4": {"due_min": 480}, "5": {"due_min": 240, "pause": False}}}
NOVE = {"valid_from": "2026-01-01", "days": {str(d): {"due_min": 540} for d in (1, 2, 3, 4)}}


def test_solo_l_amministratore(app, phone):
    assert phone.admin(app, "GET", "/api/v1/admin/system/users").status_code == 403
    assert phone.admin(app, "POST", "/api/v1/admin/system/schedules", {"name": "x", "version": V1}).status_code == 403
    me = app.get("/api/v1/me", headers=phone.auth).json()
    assert me["role"] == "user" and me["username"] == "io"
    with app.app.state.db.session() as s:
        accounts.set_role(s, me["user_id"], "admin")
    assert phone.admin(app, "GET", "/api/v1/admin/system/users").status_code == 200
    assert app.get("/api/v1/admin/system/users", headers=phone.auth).status_code == 401  # serve anche l'impronta


def test_utenti_e_telefoni(app, phone):
    _, me = give_schedule(app, phone, {"versions": [V1]})
    r = phone.admin(app, "POST", "/api/v1/admin/system/users",
                    {"username": "rossi", "full_name": "Anna Rossi", "employee_number": "42"})
    assert r.status_code == 200, r.text
    rossi = r.json()
    assert rossi["role"] == "user" and rossi["profile"]["full_name"] == "Anna Rossi"
    assert phone.admin(app, "POST", "/api/v1/admin/system/users", {"username": "rossi"}).status_code == 409
    assert phone.admin(app, "POST", "/api/v1/admin/system/users", {"username": "Con Spazi"}).status_code == 422

    # il codice nasce legato all'utente: il telefono che lo usa diventa suo
    code = phone.admin(app, "POST", f"/api/v1/admin/system/users/{rossi['id']}/pairing-code").json()["code"]
    other = Phone()
    reg = app.post("/api/v1/devices/register", json=other.registration(code, "telefono di Anna")).json()
    with app.app.state.db.session() as s:
        assert s.get(Device, reg["device_id"]).user_id == rossi["id"]
    users = {u["username"]: u for u in phone.admin(app, "GET", "/api/v1/admin/system/users").json()}
    assert [d["name"] for d in users["rossi"]["devices"]] == ["telefono di Anna"]

    # ruoli: l'ultimo amministratore non può togliersi il ruolo
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/users/{me}/role", {"role": "user"}).status_code == 422
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/users/{rossi['id']}/role", {"role": "capo"}).status_code == 422
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/users/{rossi['id']}/role", {"role": "admin"}).status_code == 200
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/users/{me}/role", {"role": "user"}).status_code == 200

    # revoca di un telefono (non quello in uso)
    assert phone.admin(app, "DELETE", f"/api/v1/admin/system/devices/{phone.device_id}").status_code == 403  # non più admin


def test_revoca_del_telefono(app, phone):
    _, me = give_schedule(app, phone, {"versions": [V1]})
    assert phone.admin(app, "DELETE", f"/api/v1/admin/system/devices/{phone.device_id}").status_code == 422  # è il mio
    from timbro import services

    with app.app.state.db.session() as s:
        u = s.get(User, me)
        c = services.new_pairing_code(s, u, app.app.state.settings)
    second = Phone()
    dev = app.post("/api/v1/devices/register", json=second.registration(c, "vecchio")).json()["device_id"]
    r = phone.admin(app, "DELETE", f"/api/v1/admin/system/devices/{dev}")
    assert r.status_code == 200 and r.json()["revoked_at"]
    assert phone.admin(app, "DELETE", "/api/v1/admin/system/devices/inesistente").status_code == 404


def test_articolazioni_condivise_e_assegnazioni(app, phone):
    sid, me = give_schedule(app, phone, {"versions": [V1]}, name="36 h su 5 giorni")
    r = phone.admin(app, "POST", "/api/v1/admin/system/schedules", {"name": "4 giorni da 9 ore", "version": NOVE})
    nove = r.json()["id"]
    assert phone.admin(app, "POST", "/api/v1/admin/system/schedules",
                       {"name": "36 H SU 5 GIORNI", "version": V1}).status_code == 422  # nome già usato

    # l'ente cambia la pausa minima per tutti dal 2026-06-01: una versione nuova dell'articolazione condivisa
    v2 = {**V1, "valid_from": "2026-06-01", "pause_counted_min": 20}
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/schedules/{sid}/versions", v2).status_code == 200
    # dal 2026-09-01 io passo ai 4 giorni da 9 ore
    r = phone.admin(app, "PUT", f"/api/v1/admin/system/users/{me}/assignments", [
        {"schedule_id": sid, "valid_from": "2026-01-01"}, {"schedule_id": nove, "valid_from": "2026-09-01"}])
    assert [a["schedule_name"] for a in r.json()] == ["36 h su 5 giorni", "4 giorni da 9 ore"]

    resolved = orario.Schedules.model_validate(app.get("/api/v1/schedule", headers=phone.auth).json())
    assert [v.valid_from.isoformat() for v in resolved.versions] == ["2026-01-01", "2026-06-01", "2026-09-01"]
    assert resolved.at(date(2026, 3, 2)).pause_counted_min == 30
    assert resolved.at(date(2026, 7, 6)).pause_counted_min == 20
    assert resolved.at(date(2026, 9, 7)).days[1].due_min == 540 and 5 not in resolved.at(date(2026, 9, 7)).days

    # versioni e articolazioni: niente articolazione senza versioni, niente cancellazione se assegnata
    assert phone.admin(app, "DELETE", f"/api/v1/admin/system/schedules/{nove}/versions/2026-01-01").status_code == 422
    assert phone.admin(app, "DELETE", f"/api/v1/admin/system/schedules/{sid}/versions/2026-06-01").status_code == 200
    assert phone.admin(app, "DELETE", f"/api/v1/admin/system/schedules/{nove}").status_code == 422
    assert phone.admin(app, "PUT", f"/api/v1/admin/system/users/{me}/assignments",
                       [{"schedule_id": 999, "valid_from": "2026-01-01"}]).status_code == 404
    names = [x["name"] for x in phone.admin(app, "GET", "/api/v1/admin/system/schedules").json()]
    assert names == ["36 h su 5 giorni", "4 giorni da 9 ore"]
    r = phone.admin(app, "PUT", f"/api/v1/admin/system/schedules/{nove}/name", {"name": "36 h su 4 giorni"})
    assert r.json()["name"] == "36 h su 4 giorni"


def test_profilo_dalle_tabelle(app, phone):
    p = {"full_name": "Mario Rossi", "employee_number": "1", "contest_recipients": ["a@b.it", "c@d.it"]}
    assert phone.admin(app, "PUT", "/api/v1/admin/profile", p).json()["contest_recipients"] == ["a@b.it", "c@d.it"]
    # stesso indirizzo di nuovo, in un altro ordine: nessun conflitto sul vincolo unico
    p["contest_recipients"] = ["c@d.it", "a@b.it"]
    assert phone.admin(app, "PUT", "/api/v1/admin/profile", p).json()["contest_recipients"] == ["c@d.it", "a@b.it"]


def test_migrazione_copia_profili_e_orari(database_url):
    """La release precedente teneva profilo e orario in settings: la migrazione li copia nelle tabelle, e gli orari
    uguali diventano una sola articolazione condivisa."""
    db = Database(database_url)
    with db.engine.begin() as conn:
        command.upgrade(cfg_for(db, conn), "ad817c614dab")
        conn.execute(text("insert into users (id, username, created_at) values (1, 'a', now()), (2, 'b', now()), "
                          "(3, 'c', now())"))
        same = json.dumps({"versions": [{**V1, "entry_from": "07:30:00", "entry_to": "09:30:00", "pause_from": "12:15:00",
                                         "pause_to": "13:15:00", "pause_counted_min": 30, "pause_terminal_min": 15,
                                         "no_pause_after_min": 360, "no_pause_deduct_min": 30}]})
        rows = {
            "profilo:1": json.dumps({"full_name": "A", "employee_number": "1", "contest_recipients": ["x@y.it"]}),
            "orario:1": same, "orario:2": same,
            "orario:3": json.dumps({"versions": [NOVE]}),
        }
        for k, v in rows.items():
            conn.execute(text("insert into settings (key, value) values (:k, :v)"), {"k": k, "v": v})
        command.upgrade(cfg_for(db, conn), "head")
    with db.session() as s:
        assert accounts.get_profile(s, 1) == {"full_name": "A", "employee_number": "1", "email": None,
                                              "contest_recipients": ["x@y.it"]}
        a1, a2, a3 = (accounts.assignments_out(s, u) for u in (1, 2, 3))
        assert a1[0]["schedule_id"] == a2[0]["schedule_id"] != a3[0]["schedule_id"]  # condivisa
        assert accounts.resolved_schedules(s, 3).at(date(2026, 5, 4)).days[1].due_min == 540
        assert s.scalar(select(User.role).where(User.id == 1)) == "user"
        # le righe vecchie di settings non servono più a nessuna versione che può ripartire: tolte
        assert s.scalar(text("select count(*) from settings where key like 'profilo:%' or key like 'orario:%'")) == 0
    db.dispose()

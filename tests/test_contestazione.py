"""Contestazione di una timbratura: profilo, link con tutta la giornata, mail (SMTP finto), pagina pubblica."""

import smtplib
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from tests.helpers import Phone, give_schedule
from tests.test_storico_app import add
from timbro import mailer, problems, services
from timbro.app import create_app
from timbro.config import Settings
from timbro.db import utcnow
from timbro.models import ContestNotice, Punch
from timbro.worker import Worker

PROFILE = {"full_name": "Mario Rossi", "employee_number": "012345", "email": "mario.personale@example.it",
           "contest_recipients": ["personale@ente.example.it", "capo@ente.example.it"]}


class FakeSMTP:
    """Server SMTP finto: registra i messaggi, o fallisce come richiesto."""

    sent: list = []
    fail: Exception | None = None

    def __init__(self, host, port, timeout=None, context=None):
        self.host, self.port = host, port

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def login(self, user, password):
        self.user, self.password = user, password

    def send_message(self, msg):
        if FakeSMTP.fail is not None:
            raise FakeSMTP.fail
        FakeSMTP.sent.append(msg)
        return {}


@pytest.fixture
def smtp(monkeypatch):
    FakeSMTP.sent, FakeSMTP.fail = [], None
    monkeypatch.setattr(mailer.smtplib, "SMTP_SSL", FakeSMTP)
    return FakeSMTP


def make_app(tmp_path, database_url, **kw):
    settings = Settings(
        database_url=database_url, data_dir=tmp_path, worker_enabled=False, attestation_required=False, _env_file=None,
        public_base_url="https://timbro.example.it", **kw,
    )
    return TestClient(create_app(settings))


def pair(client) -> Phone:
    with client.app.state.db.session() as s:
        code = services.new_pairing_code(s, services.create_user(s, "io"), client.app.state.settings)
    ph = Phone()
    r = client.post("/api/v1/devices/register", json=ph.registration(code)).json()
    ph.device_id, ph.token = r["device_id"], r["api_token"]
    return ph


@pytest.fixture
def env(tmp_path, database_url, smtp):
    with make_app(tmp_path, database_url, smtp_host="smtp.example.it", smtp_user="mario.rossi@example.it",
                  smtp_password="segreta", smtp_from="Mario Rossi <mario.rossi@example.it>") as client:
        yield client, pair(client)


def day(client, ph):
    """Una giornata con due entrate e due uscite, più una timbratura di un altro giorno."""
    ids = [
        add(client, ph, "in", "2026-11-04T07:58:12+01:00"),
        add(client, ph, "out", "2026-11-04T12:31:00+01:00"),
        add(client, ph, "in", "2026-11-04T13:00:05+01:00"),
        add(client, ph, "out", "2026-11-04T17:02:40+01:00"),
    ]
    other = add(client, ph, "in", "2026-11-03T08:00:00+01:00")
    return ids, other


def test_profilo(env):
    client, ph = env
    r = ph.admin(client, "GET", "/api/v1/admin/profile").json()
    assert r == {"full_name": None, "employee_number": None, "email": None, "contest_recipients": [], "mail_configured": True}
    r = ph.admin(client, "PUT", "/api/v1/admin/profile", {**PROFILE, "contest_recipients": PROFILE["contest_recipients"] * 2})
    assert r.status_code == 200 and r.json()["contest_recipients"] == PROFILE["contest_recipients"]  # senza doppioni
    bad = ph.admin(client, "PUT", "/api/v1/admin/profile", {**PROFILE, "contest_recipients": ["non-una-mail"]})
    assert bad.status_code == 422
    many = ph.admin(client, "PUT", "/api/v1/admin/profile", {**PROFILE, "contest_recipients": [f"a{i}@b.it" for i in range(11)]})
    assert many.status_code == 422
    assert ph.admin(client, "PUT", "/api/v1/admin/profile", {**PROFILE, "email": "non valida"}).status_code == 422
    assert client.get("/api/v1/admin/profile", headers=ph.auth).status_code == 401  # serve la firma admin


def test_contestazione_con_mail(env, smtp):
    client, ph = env
    ids, other = day(client, ph)
    ph.admin(client, "PUT", "/api/v1/admin/profile", PROFILE)
    r = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[1]}/contest", {"days": 7, "note": "Il terminale non ha registrato l'uscita."})
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["punch"]["contested"] is True
    assert out["share"]["punch_ids"] == ids and other not in out["share"]["punch_ids"]  # tutta la giornata, solo quella
    assert out["share"]["url"].startswith("https://timbro.example.it/s/")
    n = out["notice"]
    assert n["status"] == "pending" and n["recipients"] == PROFILE["contest_recipients"]
    assert n["subject"] == "Timbrature del 04/11/2026: segnalazione di disallineamento - Mario Rossi, matricola 012345"

    Worker(client.app.state.db, client.app.state.settings).send_notice(n["id"])
    msg = smtp.sent[0]
    body = msg.get_content()
    assert msg["To"] == "personale@ente.example.it, capo@ente.example.it"
    assert msg["Reply-To"] == msg["Cc"] == "mario.personale@example.it"  # le risposte e una copia a chi segnala
    assert msg["From"] == "Mario Rossi <mario.rossi@example.it>"
    assert "Gentile ufficio,\n\nMario Rossi, matricola 012345, segnala un disallineamento" in body
    assert "Le timbrature della giornata sono: entrata 07:58, uscita 12:31, entrata 13:00, uscita 17:02.\n" in body
    assert "mercoledì 4 novembre 2026" in body and "l'uscita delle 12:31" in body
    assert out["share"]["url"] in body and "Nota: Il terminale non ha registrato l'uscita." in body
    valid_until = (utcnow() + timedelta(days=7)).astimezone(services.LOCAL_TZ).strftime("%d/%m/%Y")
    assert f"valido fino al {valid_until}" in body
    detail = ph.admin(client, "GET", f"/api/v1/admin/punches/{ids[1]}").json()
    assert detail["notices"][0]["status"] == "sent" and detail["notices"][0]["sent_at"]


def test_contestazione_senza_destinatari_crea_solo_il_link(tmp_path, database_url):
    with make_app(tmp_path, database_url) as client:  # SMTP non configurato
        ph = pair(client)
        ids, _ = day(client, ph)
        r = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[0]}/contest", {})
        assert r.status_code == 200 and r.json()["notice"] is None
        assert r.json()["share"]["punch_ids"] == ids
        # con destinatari ma senza SMTP: niente di fatto
        r = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[0]}/contest", {"recipients": ["a@b.it"]})
        assert r.status_code == 409 and "SMTP" in r.json()["detail"]
        assert ph.admin(client, "GET", "/api/v1/admin/profile").json()["mail_configured"] is False


def test_contestazione_rifiutata_senza_profilo_o_con_input_sbagliati(env):
    client, ph = env
    ids, _ = day(client, ph)
    r = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[0]}/contest", {"recipients": ["a@b.it"]})
    assert r.status_code == 409 and "matricola" in r.json()["detail"]
    ph.admin(client, "PUT", "/api/v1/admin/profile", PROFILE)
    assert ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[0]}/contest", {"days": 61}).status_code == 422
    assert ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[0]}/contest", {"recipients": ["x"]}).status_code == 422
    assert ph.admin(client, "POST", "/api/v1/admin/punches/999999/contest", {}).status_code == 404
    with client.app.state.db.session() as s:
        assert s.scalar(select(ContestNotice.id)) is None
        assert not s.get(Punch, ids[0]).contested


def test_errori_temporanei_e_definitivi(env, smtp):
    client, ph = env
    ids, _ = day(client, ph)
    ph.admin(client, "PUT", "/api/v1/admin/profile", PROFILE)
    nid = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[1]}/contest", {}).json()["notice"]["id"]
    w = Worker(client.app.state.db, client.app.state.settings)

    smtp.fail = smtplib.SMTPAuthenticationError(535, b"password sbagliata")  # si sistema il .env e si riprova
    w.send_notice(nid)
    with client.app.state.db.session() as s:
        n = s.get(ContestNotice, nid)
        assert n.status == "retrying" and n.attempts == 1 and n.next_attempt_at > utcnow()
        s.execute(update(ContestNotice).values(created_at=utcnow() - timedelta(hours=1)))
        s.commit()
    with client.app.state.db.session() as s:
        found = problems.find(s, client.app.state.settings, utcnow(), problems.AppState(worker_enabled=False))
    alert = [p for p in found if p.key.startswith("mail-contestazione:")]
    assert alert and "SMTPAuthenticationError" in alert[0].text and "@" not in alert[0].text  # niente indirizzi

    smtp.fail = smtplib.SMTPRecipientsRefused({"personale@ente.example.it": (550, b"no"), "capo@ente.example.it": (550, b"no")})
    with client.app.state.db.session() as s:
        s.execute(update(ContestNotice).values(next_attempt_at=utcnow()))
        s.commit()
    w.run_once(wait=True)  # il ciclo del worker la riprende
    with client.app.state.db.session() as s:
        n = s.get(ContestNotice, nid)
        assert n.status == "failed" and "550" in n.error
    w.stop()


# --- pagina pubblica -----------------------------------------------------------------------------------------------


def test_pagina_della_contestazione(env):
    client, ph = env
    ids, _ = day(client, ph)
    ph.admin(client, "PUT", "/api/v1/admin/profile", PROFILE)
    out = ph.admin(client, "POST", f"/api/v1/admin/punches/{ids[1]}/contest",
                   {"recipients": [], "note": "<script>alert(1)</script> uscita non registrata"}).json()
    path = "/" + out["share"]["url"].split("://", 1)[1].split("/", 1)[1]
    r = client.get(path)
    assert r.status_code == 200
    page = r.text
    assert "Mario Rossi" in page and "012345" in page
    assert "Segnalazione di disallineamento" in page and "CONTESTATA" in page
    i = page.find("<script")
    assert i < 0, page[max(0, i - 300):i + 100]
    assert "&lt;script&gt;" in page  # la nota è testo, non HTML
    assert page.count('class="card punch"') == 4  # tutta la giornata
    for info in ("inalterabili", "video", "marca", "ots", "firma", "attestazione", "posizione", "catena", "verifica", "probatorio", "liceita"):
        assert f'id="info-{info}"' in page and f'popovertarget="info-{info}"' in page
    assert "art. 2712 c.c." in page and "art. 41, par. 2" in page and "openstreetmap.org" in page
    assert "Play App Signing" in page  # la firma di Google Play spiegata nell'attestazione
    assert "pausa 29" in page  # 12:31 -> 13:00
    csp = r.headers["content-security-policy"]
    assert "font-src 'self'" in csp and "script-src" not in csp  # nessuno script: le schede sono popover HTML


def test_caratteri_serviti_dal_server(env):
    client, _ = env
    r = client.get("/s/static/fonts/unbounded.woff2")
    assert r.status_code == 200 and r.headers["content-type"] == "font/woff2"
    assert "max-age" in r.headers["cache-control"]
    assert client.get("/s/static/fonts/../../config.py").status_code == 404
    assert client.get("/s/static/fonts/nonesiste.woff2").status_code == 404


def test_testo_della_mail_uguale_all_app():
    """Testo esatto delle mail (tests/fixtures/contratto/mail.json): lo stesso deve uscire dall'anteprima dell'app."""
    import json
    from datetime import date, datetime
    from pathlib import Path

    from timbro import orario

    fixtures = Path(__file__).parent / "fixtures" / "contratto"
    schedules = orario.Schedules.model_validate(json.loads((fixtures / "orario.json").read_text(encoding="utf-8"))["schedule"])
    for c in json.loads((fixtures / "mail.json").read_text(encoding="utf-8"))["cases"]:
        d = date.fromisoformat(c["day"])
        marks = [(k, datetime.fromisoformat(f"{c['day']}T{hm}")) for k, hm in c["marks"]]
        hours = orario.compute([orario.Mark(k, t) for k, t in marks], schedules.at(d)) if c["with_schedule"] else None
        punch = (c["punch"][0], datetime.fromisoformat(f"{c['day']}T{c['punch'][1]}")) if c["punch"] else None
        m = mailer.compose(c["full_name"], c["employee_number"], d, punch, marks, hours, c["url"],
                           date.fromisoformat(c["valid_until"]), c["note"])
        assert (m.subject, m.body) == (c["subject"], c["body"]), c["name"]


SCHEDULE = {"versions": [{"valid_from": "2026-01-01", "days": {
    "1": {"due_min": 480}, "2": {"due_min": 480}, "3": {"due_min": 480}, "4": {"due_min": 480},
    "5": {"due_min": 240, "pause": False}}}]}


def test_segnalazione_della_giornata_intera(env, smtp):
    client, ph = env
    ids, other = day(client, ph)
    ph.admin(client, "PUT", "/api/v1/admin/profile", PROFILE)
    give_schedule(client, ph, SCHEDULE)
    r = ph.admin(client, "POST", "/api/v1/admin/days/2026-11-04/contest", {"days": 30, "note": "Ore calcolate male."})
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["day"] == "2026-11-04" and out["punch"] is None
    assert out["share"]["punch_ids"] == ids
    assert out["hours"] == {"worked_min": 514, "due_min": 480, "balance_min": 34, "complete": True, "open": False,
                            "adjustments": [{"code": "pausa_minima", "params": {"durata": 29, "conteggiata": 30}}],
                            "anomalies": []}
    assert out["notice"]["day"] == "2026-11-04" and out["notice"]["punch_id"] is None
    with client.app.state.db.session() as s:
        assert not any(s.get(Punch, i).contested for i in ids)  # nessuna timbratura in particolare
    Worker(client.app.state.db, client.app.state.settings).send_notice(out["notice"]["id"])
    body = smtp.sent[0].get_content()
    assert ("quanto effettivamente avvenuto.\n\nLe timbrature della giornata sono: entrata 07:58, uscita 12:31, "
            "entrata 13:00, uscita 17:02. Secondo l'articolazione oraria in vigore risultano 8 h 34 di lavoro valido, "
            "a fronte di 8 h 00 dovute (saldo +34′).") in body

    page = client.get("/" + out["share"]["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert "Giornata segnalata: <b>mercoledì 4 novembre 2026</b>" in page
    assert "8 h 34" in page and "+34′" in page and "pausa di 29′ conteggiata 30′" in page
    assert 'id="info-ore-2026-11-04"' in page and "Ore calcolate male." in page

    notices = ph.admin(client, "GET", "/api/v1/admin/notices").json()  # anche quelle della giornata intera
    assert notices[0]["day"] == "2026-11-04" and notices[0]["punch_id"] is None
    assert client.get("/api/v1/me", headers=ph.auth).json()["mail_configured"] is True  # senza impronta
    assert ph.admin(client, "POST", "/api/v1/admin/days/2026-11-01/contest", {}).status_code == 422  # giorno vuoto
    assert ph.admin(client, "POST", "/api/v1/admin/days/0001-01-01/contest", {}).status_code == 422

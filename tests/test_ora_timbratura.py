"""Ora della timbratura (docs/PROGETTO.md §24): l'ora del telefono si sposta dalle impostazioni, quindi vale solo se la
conferma la ricezione immediata o un orologio che l'utente non controlla (rete, satelliti) scritto nel manifest
firmato. Altrimenti vale l'orologio che la smentisce o la ricezione, e parte l'avviso."""

import io
import os
import zipfile
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from tests.helpers import body
from timbro import services
from timbro import verifica_pacchetto as vp
from timbro.models import Punch

ROME = ZoneInfo("Europe/Rome")
DEFAULT = object()


def iso(t: datetime) -> str:
    return t.astimezone(ROME).isoformat(timespec="milliseconds")


def send(app, phone, device_time: datetime, clocks=DEFAULT) -> int:
    video = os.urandom(1000)
    extra = {"device_time": iso(device_time)}
    if clocks is not DEFAULT:
        extra["clocks"] = clocks
    raw, sig = phone.punch(video, **extra)
    r = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth)
    assert r.status_code == 200, r.text
    assert app.put(f"/api/v1/punches/{r.json()['id']}/video", content=video, headers=phone.auth).status_code == 200
    return r.json()["id"]


def check(app, pid) -> services.PunchTime:
    with app.app.state.db.session() as s:
        return services.time_check(s.get(Punch, pid))


def received(app, pid) -> datetime:
    with app.app.state.db.session() as s:
        return s.get(Punch, pid).server_received_at


def test_inviata_subito_vale_l_ora_del_telefono(app, phone):
    t = datetime.now(ROME) - timedelta(seconds=20)
    pid = send(app, phone, t, clocks=None)
    tc = check(app, pid)
    assert tc.time == t.replace(microsecond=t.microsecond // 1000 * 1000)
    assert tc.confirmed_by == "dalla ricezione immediata" and tc.warning is None


def test_dalla_coda_confermata_dall_orologio_di_rete(app, phone):
    t = datetime.now(ROME) - timedelta(hours=3)  # offline per tre ore: arriva dopo
    pid = send(app, phone, t, clocks={"auto_time": True, "network": iso(t), "gnss": iso(t + timedelta(seconds=1))})
    tc = check(app, pid)
    assert abs((tc.time - t).total_seconds()) < 1
    assert tc.confirmed_by == "dall'orologio satellitare e di rete" and tc.warning is None


def test_dalla_coda_senza_orologi_vale_la_ricezione(app, phone):
    pid = send(app, phone, datetime.now(ROME) - timedelta(hours=3), clocks=None)  # app vecchia
    tc = check(app, pid)
    assert tc.time == received(app, pid) and tc.confirmed_by is None and tc.warning is None


def test_orologio_spostato_indietro(app, phone):
    """Arrivo alle 9:30, sposto l'orologio alle 9:10 e timbro: l'orologio di rete scritto dall'app lo smentisce."""
    now = datetime.now(ROME)
    pid = send(app, phone, now - timedelta(minutes=20), clocks={"auto_time": False, "network": iso(now)})
    tc = check(app, pid)
    assert abs((tc.time - now).total_seconds()) < 1  # vale l'orologio di rete
    assert tc.warning == "ora del telefono diversa di 20 min dall'orologio di rete: vale l'orologio di rete"
    # anche lo storico dell'app e le ore usano l'ora vera
    day = now.date().isoformat()
    items = app.get("/api/v1/history", params={"until": day, "days": 1}, headers=phone.auth).json()["days"][0]["punches"]
    assert [i["time"][:16] for i in items if i["id"] == pid] == [iso(tc.time)[:16]]


def test_orologio_avanti_vale_la_ricezione(app, phone):
    pid = send(app, phone, datetime.now(ROME) + timedelta(minutes=30), clocks=None)
    tc = check(app, pid)
    assert tc.time == received(app, pid) and "avanti di 30 min" in tc.warning


def test_orologi_in_disaccordo(app, phone):
    t = datetime.now(ROME) - timedelta(hours=1)
    pid = send(app, phone, t, clocks={"gnss": iso(t), "network": iso(t - timedelta(minutes=45))})
    tc = check(app, pid)
    assert abs((tc.time - t).total_seconds()) < 1 and tc.confirmed_by == "dall'orologio satellitare"
    assert tc.warning == "ora del telefono diversa di 45 min dall'orologio di rete"


def test_pagina_e_verifica_mostrano_l_orologio_spostato(app, phone, tmp_path, capsys):
    now = datetime.now(ROME)
    pid = send(app, phone, now - timedelta(minutes=20), clocks={"network": iso(now)})
    ok = send(app, phone, now, clocks={"network": iso(now), "auto_time": True})
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [pid], "label": "x"})
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text.replace("&#39;", "'")
    assert "ora del telefono diversa di 20 min dall'orologio di rete: vale l'orologio di rete" in page
    assert "✓ confermata dall'orologio di rete" in page  # l'altra timbratura della giornata
    assert 'id="info-ora"' in page and "L'ora della timbratura" in page

    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip").content))
    z.extractall(tmp_path / "p")
    rep = vp.verify_package(tmp_path / "p", online=False)
    out = capsys.readouterr().out
    assert rep.errors == 0, out  # l'integrità non c'entra: è l'ora dichiarata a non tornare
    assert "ATTN ora del telefono diversa di 20 min dall'orologio di rete scritto nel manifest firmato" in out
    assert "ATTN ricevuta 20 min dopo l'ora dichiarata dal telefono" not in out  # c'è un orologio, ma la smentisce
    assert "conclusione del server" in out and "vale l'orologio di rete" in out

    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{ok}/evidence.zip").content))
    z.extractall(tmp_path / "q")
    vp.verify_package(tmp_path / "q", online=False)
    out = capsys.readouterr().out
    assert "OK   ora del telefono = orologio di rete scritto nel manifest firmato" in out
    assert "OK   ricevuta dal server" in out and "inviata subito" in out

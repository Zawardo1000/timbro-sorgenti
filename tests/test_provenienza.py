"""Provenienza del codice (docs/PROGETTO.md §25): versione del server per timbratura, collegamenti alle release
pubbliche nella pagina e nel pacchetto di prova, controllo dello script di verifica contro quello pubblicato."""

import hashlib
import io
import json
import os
import zipfile
from pathlib import Path

import pytest

from tests.helpers import body
from timbro import verifica_pacchetto as vp
from timbro import versione
from timbro.models import Punch

SERVER = "1234567" + "0" * 33
REPO = "https://github.com/Zawardo1000/timbro-sorgenti"


def test_versione_da_gitdeploy(tmp_path, monkeypatch):
    f = tmp_path / ".gitdeploy-commit"
    monkeypatch.setattr(versione, "COMMIT_FILE", f)
    versione.server_commit.cache_clear()
    assert versione.server_commit() is None  # sul PC il file non c'è
    versione.server_commit.cache_clear()
    f.write_text(SERVER + "\n")
    assert versione.server_commit() == SERVER
    versione.server_commit.cache_clear()
    f.write_text("non un commit")
    assert versione.server_commit() is None
    versione.server_commit.cache_clear()


def test_collegamenti():
    assert versione.release_url(REPO, SERVER) == REPO + "/releases/tag/timbro-1234567"
    assert versione.file_url(REPO, SERVER, "timbro/verifica_pacchetto.py") == (
        "https://raw.githubusercontent.com/Zawardo1000/timbro-sorgenti/timbro-1234567/timbro/verifica_pacchetto.py")
    assert versione.app_source("0.1.45+abcdef1") == ("abcdef1", False)
    assert versione.app_source("0.1.44+c0bbe6e-dirty") == ("c0bbe6e", True)
    assert versione.app_source("0.1.0") == (None, False)


@pytest.fixture
def server_versione(monkeypatch):
    monkeypatch.setattr(versione, "server_commit", lambda: SERVER)


def send(app, phone, app_version):
    video = os.urandom(1000)
    raw, sig = phone.punch(video, app_version=app_version)
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    assert app.put(f"/api/v1/punches/{pid}/video", content=video, headers=phone.auth).status_code == 200
    return pid


def _pagina_e_pacchetto(app, phone, tmp_path):
    pid = send(app, phone, "0.1.45+abcdef1")
    prova = send(app, phone, "0.1.44+c0bbe6e-dirty")
    with app.app.state.db.session() as s:
        assert s.get(Punch, pid).server_version == SERVER
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [pid, prova], "label": "x"})
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert f'href="{REPO}/releases/tag/timbro-abcdef1"' in page  # app costruita dalla release pubblica
    assert f'href="{REPO}/releases/tag/timbro-1234567"' in page  # server che l'ha ricevuta
    assert f'href="{REPO}/tree/timbro-abcdef1/android"' in page and f'href="{REPO}/tree/timbro-1234567/timbro"' in page
    assert "compilata in prova, senza release pubblica" in page  # app compilata in locale
    assert 'id="info-provenienza"' in page and "github.com/Zawardo1000/timbro-sorgenti" in page

    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip").content))
    prov = json.loads(z.read("record.json"))["provenienza"]
    assert prov["app"]["release"] == REPO + "/releases/tag/timbro-abcdef1"
    assert prov["server_alla_ricezione"] == {"commit": SERVER, "release": REPO + "/releases/tag/timbro-1234567",
                                             "sorgenti": REPO + "/tree/timbro-1234567/timbro"}
    assert prov["app"]["sorgenti"] == REPO + "/tree/timbro-abcdef1/android"
    assert prov["server_del_pacchetto"]["verifica_py"].endswith("/timbro-1234567/timbro/verifica_pacchetto.py")
    instructions = z.read("VERIFICA.md").decode()
    assert "## 9. Provenienza del codice" in instructions and "sha256sum verifica.py" in instructions
    z.extractall(tmp_path / "p")
    return tmp_path / "p"


def test_pagina_e_pacchetto_con_le_versioni(app, phone, server_versione, tmp_path):
    _pagina_e_pacchetto(app, phone, tmp_path)


def test_verifica_confronta_lo_script_con_quello_pubblicato(app, phone, server_versione, tmp_path, monkeypatch, capsys):
    folder = _pagina_e_pacchetto(app, phone, tmp_path)
    capsys.readouterr()
    script = (folder / "verifica.py").read_bytes()
    raw = "https://raw.githubusercontent.com/Zawardo1000/timbro-sorgenti/timbro-1234567/timbro/verifica_pacchetto.py"
    api = "https://api.github.com/repos/Zawardo1000/timbro-sorgenti/releases/tags/"
    pages = {
        raw: script,
        api + "timbro-abcdef1": json.dumps({"html_url": REPO + "/releases/tag/timbro-abcdef1",
                                            "published_at": "2026-11-06T08:00:00Z", "immutable": True}).encode(),
        api + "timbro-1234567": json.dumps({"html_url": REPO + "/releases/tag/timbro-1234567",
                                            "published_at": "2026-11-06T07:00:00Z", "immutable": True}).encode(),
    }

    def fake_get(url):
        if url in pages:
            return pages[url]
        raise OSError("niente rete nei test: " + url)

    monkeypatch.setattr(vp, "_get", fake_get)
    monkeypatch.setattr(vp, "__file__", str(folder / "verifica.py"))
    rep = vp.verify_package(folder, online=True)
    out = capsys.readouterr().out
    assert "OK   questo script è identico a quello pubblicato per il server 1234567" in out
    assert "OK   release pubblica di app: " + REPO + "/releases/tag/timbro-abcdef1, pubblicata il 2026-11-06, immutabile" in out
    assert hashlib.sha256(script).hexdigest() in out
    errors = rep.errors

    pages[raw] = script + b"\n# modificato\n"  # lo script del pacchetto non è più quello pubblicato
    rep = vp.verify_package(folder, online=True)
    out = capsys.readouterr().out
    assert rep.errors == errors + 1 and "ERR  questo script è identico a quello pubblicato" in out
    assert Path(vp.__file__).name == "verifica.py"

    # in caso di dubbi: lo script scaricato da GitHub, lanciato da fuori sulla cartella del pacchetto, controlla anche
    # il verifica.py arrivato col pacchetto
    pages[raw] = script
    fuori = tmp_path / "scaricato" / "verifica-github.py"
    fuori.parent.mkdir()
    fuori.write_bytes(script)
    monkeypatch.setattr(vp, "__file__", str(fuori))
    rep = vp.verify_package(folder, online=True)
    out = capsys.readouterr().out
    assert "OK   il verifica.py del pacchetto è identico a quello pubblicato per il server 1234567" in out
    assert rep.errors == errors
    (folder / "verifica.py").write_bytes(script + b"\n# manomesso\n")
    rep = vp.verify_package(folder, online=True)
    out = capsys.readouterr().out
    assert rep.errors == errors + 1 and "ERR  il verifica.py del pacchetto è identico a quello pubblicato" in out

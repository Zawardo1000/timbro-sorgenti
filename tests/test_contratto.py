"""Contratto fra app e server: esempi condivisi con i test Kotlin di android/core (tests/fixtures/contratto).

Se un test qui fallisce dopo una modifica dell'API, vanno aggiornati insieme gli esempi e i modelli Kotlin
(android/core, pacchetto api): l'app non va in CI, questo è l'unico controllo automatico del contratto.
"""

import base64
import json
import os
from datetime import datetime
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from tests.helpers import Phone, body, give_schedule
from timbro import services
from timbro.api.admin_auth import NONCE_RE, canonical

FIXTURES = Path(__file__).parent / "fixtures" / "contratto"


def test_esempi_di_firma_admin():
    data = json.loads((FIXTURES / "firma_admin.json").read_text(encoding="utf-8"))
    key = serialization.load_pem_public_key(data["public_key_pem"].encode())
    for v in data["vectors"]:
        msg = canonical(v["method"], v["path"], v["query"], v["time"], v["nonce"], v["body"].encode("utf-8"))
        assert msg.decode() == v["canonical"]
        key.verify(base64.b64decode(v["signature"]), msg, ec.ECDSA(hashes.SHA256()))
        assert datetime.fromisoformat(v["time"]).tzinfo is not None  # formato dell'ora dell'app
        assert NONCE_RE.match(v["nonce"])


def _same_shape(example, actual, where="$"):
    """Stessi campi a ogni livello; stessi tipi quando nessuno dei due è null."""
    if example is None or actual is None:
        return
    if isinstance(example, dict) and example and all(k.isdigit() for k in example):
        # mappa con chiavi variabili (es. giorni della settimana dell'orario): conta la forma dei valori
        assert isinstance(actual, dict), where
        for e in example.values():
            for a in list(actual.values())[:1]:
                _same_shape(e, a, f"{where}{{}}")
    elif isinstance(example, dict):
        assert isinstance(actual, dict), where
        assert set(example) == set(actual), f"{where}: esempio {sorted(example)} != vero {sorted(actual)}"
        for k in example:
            _same_shape(example[k], actual[k], f"{where}.{k}")
    elif isinstance(example, list):
        assert isinstance(actual, list), where
        for e in example:
            for a in actual[:1]:
                _same_shape(e, a, f"{where}[]")
    else:
        assert type(example) is type(actual), f"{where}: {type(example).__name__} != {type(actual).__name__}"


def test_esempi_di_risposta_uguali_al_server(app):
    examples = json.loads((FIXTURES / "risposte.json").read_text(encoding="utf-8"))
    with app.app.state.db.session() as s:
        code = services.new_pairing_code(s, services.create_user(s, "io"), app.app.state.settings)
    ph = Phone()
    reg = app.post("/api/v1/devices/register", json=ph.registration(code)).json()
    _same_shape(examples["register"], reg, "register")
    ph.device_id, ph.token = reg["device_id"], reg["api_token"]

    video = os.urandom(1500)
    raw, sig = ph.punch(video, kind="out")
    status = app.post("/api/v1/punches", json=body(raw, sig), headers=ph.auth).json()
    assert status["stamps"], "servono marche per confrontarne i campi"
    _same_shape(examples["punch_status"], status, "punch_status")
    _same_shape(examples["punch_status"], app.get(f"/api/v1/punches/{status['id']}", headers=ph.auth).json(), "get")

    history = app.get("/api/v1/history", headers=ph.auth).json()
    assert history["days"] and history["days"][0]["punches"]
    _same_shape(examples["history"], history, "history")

    # orario (scritto con la firma admin, letto con il solo token) e contestazione con il calcolo delle ore
    give_schedule(app, ph, examples["schedule"])
    schedule = app.get("/api/v1/schedule", headers=ph.auth).json()
    assert schedule == examples["schedule"]  # un'assegnazione dalla prima versione: le versioni tornano uguali
    _same_shape(examples["schedule"], schedule, "schedule")
    for key, url in (("me", "/api/v1/me"),):
        _same_shape(examples[key], app.get(url, headers=ph.auth).json(), key)
    for key, url in (("system_users", "/api/v1/admin/system/users"), ("system_schedules", "/api/v1/admin/system/schedules"),
                     ("notices", "/api/v1/admin/notices")):
        _same_shape(examples[key], ph.admin(app, "GET", url).json(), key)
    profile = ph.admin(app, "PUT", "/api/v1/admin/profile", examples["profile"]).json()
    _same_shape(examples["profile"], profile, "profile")
    contest = ph.admin(app, "POST", f"/api/v1/admin/punches/{status['id']}/contest", {"recipients": []}).json()
    assert contest["hours"] is not None and contest["notice"] is None  # senza SMTP: solo il link
    _same_shape(examples["contest"], contest, "contest")

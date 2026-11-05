"""Attestazione hardware di ogni timbratura (Android Key Attestation) e reti Wi-Fi nel manifest."""

import io
import json
import os
import subprocess
import sys
import zipfile
from datetime import UTC, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from tests.helpers import AttestationCA, Phone, body
from timbro import attestation, checks, problems, services
from timbro.app import create_app
from timbro.config import Settings
from timbro.db import utcnow
from timbro.models import Punch, Stamp

FIXTURES = Path(__file__).parent / "fixtures" / "attestation"
APP_DIGEST = "11" * 32


@pytest.fixture
def env(tmp_path, database_url):
    ca = AttestationCA(tmp_path / "radici")
    settings = Settings(
        database_url=database_url, data_dir=tmp_path / "dati", worker_enabled=False, _env_file=None,
        attestation_roots_dir=tmp_path / "radici", attestation_required=True,
        attestation_package="it.test.timbro", attestation_signing_cert_sha256=[APP_DIGEST],
    )
    with TestClient(create_app(settings)) as client:
        with client.app.state.db.session() as s:
            code = services.new_pairing_code(s, services.create_user(s, "io"), settings)
        ph = Phone()
        r = client.post("/api/v1/devices/register", json=ph.registration(code)).json()
        ph.device_id, ph.token = r["device_id"], r["api_token"]
        yield client, ph, ca


def send(client, ph, attestation=None, video=None, **extra):
    video = video or os.urandom(2000)
    raw, sig = ph.punch(video, **extra)
    r = client.post("/api/v1/punches", json=body(raw, sig, attestation(raw) if callable(attestation) else attestation),
                    headers=ph.auth)
    assert r.status_code == 200, r.text
    client.put(f"/api/v1/punches/{r.json()['id']}/video", content=video, headers=ph.auth)
    return r.json(), raw


def problem_keys(client) -> set[str]:
    with client.app.state.db.session() as s:
        found = problems.find(s, client.app.state.settings, utcnow(), problems.AppState(worker_enabled=False))
    return {p.key for p in found if p.key.startswith("attestazione")}


def test_attestazione_valida(env, tmp_path):
    client, ph, ca = env
    st, raw = send(client, ph, lambda raw: ca.chain(raw))
    assert st["attestation"] == {"ok": True, "problems": []}
    with client.app.state.db.session() as s:
        p = s.get(Punch, st["id"])
        r = json.loads(p.attestation_json)
        targets = {(x.target, x.provider) for x in s.scalars(select(Stamp).where(Stamp.punch_id == p.id))}
    assert r["security_level"] == "tee" and r["device_locked"] and r["verified_boot_state"] == "verified"
    assert r["packages"] == [["it.test.timbro", 1]] and r["challenge_ok"] and r["os_patch_level"] == 202610
    # la catena ha le sue marche: prova che esisteva entro quell'ora
    assert {("attest", "accv"), ("attest", "sectigo-q"), ("attest", "ots")} <= targets
    assert problem_keys(client) == set()

    # pacchetto di prova: catena, radici, challenge controllata da verifica.py
    detail = ph.admin(client, "GET", f"/api/v1/admin/punches/{st['id']}").json()
    assert detail["attestation_detail"]["ok"]
    z = zipfile.ZipFile(io.BytesIO(ph.admin(client, "GET", f"/api/v1/admin/punches/{st['id']}/evidence.zip").content))
    names = set(z.namelist())
    assert {"attestazione/catena.pem", "attestazione/foglia.pem", "attestazione/radici_google.pem"} <= names
    assert "openssl verify -no_check_time" in z.read("VERIFICA.md").decode()
    rec = json.loads(z.read("record.json"))
    assert rec["attestazione"]["esito"]["ok"]
    out_dir = tmp_path / "pacchetto"
    z.extractall(out_dir)
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=out_dir, capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
    assert "OK   attestazione: challenge = SHA-256 di manifest.json" in out.stdout
    assert "it.test.timbro" in out.stdout
    assert "OK   chiave di attestazione emessa da Google" in out.stdout  # intermedio senza serialNumber

    # in rete le radici vengono da Google: la radice di prova del test non ci sta, e lo script lo dice
    from timbro import verifica_pacchetto as vp

    google = [p.read_text() for p in sorted((Path(attestation.__file__).parent / "attestation_roots").glob("*.pem"))]
    pages = {vp.FONTI["google"]: json.dumps(google).encode(), vp.FONTI["revoche"]: b'{"entries": {}}'}
    vp_get = vp._get
    try:
        vp._get = lambda url: pages[url] if url in pages else (_ for _ in ()).throw(OSError("niente rete nei test"))
        rep = vp.verify_package(out_dir, online=True)
        assert rep.errors >= 1  # catena di prova: non termina con una radice di Google
        test_root = (tmp_path / "radici" / "radice-di-prova.pem").read_text()
        pages[vp.FONTI["google"]] = json.dumps([test_root]).encode()  # come se Google pubblicasse la radice di prova
        rep = vp.verify_package(out_dir, online=True)
        assert rep.errors == 0
    finally:
        vp._get = vp_get


def test_telefono_con_bootloader_sbloccato(env):
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw, locked=False, boot=2))
    assert not st["attestation"]["ok"]
    assert any("bootloader" in x for x in st["attestation"]["problems"])
    assert any("avvio non verificato" in x for x in st["attestation"]["problems"])
    assert len(problem_keys(client)) == 1  # attestazione:<giorno>


def test_attestazione_di_un_altro_manifest(env):
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(b"un altro manifest"))
    assert any("challenge" in x for x in st["attestation"]["problems"])


def test_radice_estranea_e_app_diversa(env, tmp_path):
    client, ph, ca = env
    other = AttestationCA(tmp_path / "altre")
    st, _ = send(client, ph, lambda raw: other.chain(raw))
    assert any("radice di Google" in x for x in st["attestation"]["problems"])
    st, _ = send(client, ph, lambda raw: ca.chain(raw, package="com.falso", digest=b"\x22" * 32))
    joined = " ".join(st["attestation"]["problems"])
    assert "app diversa" in joined and "certificato atteso" in joined


def test_chiave_solo_software(env):
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw, level=0))
    assert any("hardware" in x for x in st["attestation"]["problems"])


def test_attestazione_mancante_o_illeggibile_ma_timbratura_accettata(env):
    client, ph, ca = env
    st, _ = send(client, ph)  # nessuna attestazione
    assert st["attestation"] is None
    st2, _ = send(client, ph, ["!!!non base64"])
    assert "illeggibile" in st2["attestation"]["problems"][0]
    with client.app.state.db.session() as s:
        assert s.scalar(select(Punch).where(Punch.id == st2["id"])) is not None  # la prova non si perde
    keys = problem_keys(client)
    assert len(keys) == 1
    with client.app.state.db.session() as s:
        text = [p.text for p in problems.find(s, client.app.state.settings, utcnow(), problems.AppState(worker_enabled=False))
                if p.key.startswith("attestazione")][0]
    assert "2 timbrature" in text and "attestazione mancante" in text


def test_lista_di_revoca_di_google(env, monkeypatch):
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw))
    with client.app.state.db.session() as s:
        serial = json.loads(s.get(Punch, st["id"]).attestation_json)["serials"][1]  # l'intermedio

    class FakeResponse:
        def raise_for_status(self):
            pass

        def json(self):
            return {"entries": {serial: {"status": "REVOKED", "reason": "KEY_COMPROMISE"}}}

    class FakeClient:
        def __init__(self, *a, **kw):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get(self, url):
            return FakeResponse()

    monkeypatch.setattr(checks.httpx, "Client", FakeClient)
    record = checks.run_attestation_revocations(client.app.state.db, client.app.state.settings)
    assert record["revoked"] == [{"punch_id": st["id"], "serial": serial, "status": "REVOKED", "reason": "KEY_COMPROMISE"}]
    assert any(k.startswith("attestazione-revocata:") for k in problem_keys(client))


def test_catena_reale_di_android():
    """Catena vera (repository di Google): TEE, telefono di sviluppo sbloccato, radice RSA di Google del 2016."""
    chain = [(FIXTURES / "ec_tee" / f"cert{i}.der").read_bytes() for i in range(4)]
    roots = attestation.load_roots(Path(attestation.__file__).parent / "attestation_roots")
    r = attestation.verify(chain, b"qualsiasi", roots, datetime(2026, 11, 5, tzinfo=UTC))
    # catena valida fino alla radice di Google, anche se il suo certificato radice (2016) è scaduto: conta la chiave
    assert r.root == "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae"
    assert not any("catena" in x or "validità" in x for x in r.problems), r.problems
    assert r.security_level == "tee" and r.device_locked is False and r.verified_boot_state == "unverified"
    assert ["android", 29] in r.packages and r.os_patch_level == 201907
    kd = attestation.parse_key_description(attestation.Cert(chain[0]).extension(attestation.KEY_DESCRIPTION_OID))
    assert kd.challenge == b"abc"
    # i byte originali (codifiche non canoniche comprese) finiscono identici nel PEM conservato
    assert attestation.to_pem(chain).count("BEGIN CERTIFICATE") == 4


def test_radici_di_google_fissate():
    roots = attestation.load_roots(Path(attestation.__file__).parent / "attestation_roots")
    assert set(roots) == {
        "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",  # RSA (2016, 2019, 2021, 2022: stessa chiave)
        "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",  # EC "Key Attestation CA1" (2025)
    }


# --- reti Wi-Fi ----------------------------------------------------------------------------------------------------


def wifi(*bssids, rssi=-60):
    return {"scan_time": "2026-11-05T07:58:00+01:00", "aps": [{"bssid": b, "rssi": rssi, "mhz": 2437} for b in bssids]}


def test_reti_wifi_della_sede(env):
    client, ph, ca = env
    sede = ["00:1a:2b:3c:4d:5e", "00:1a:2b:3c:4d:5f"]
    hotspot = "da:a1:19:00:00:01"  # MAC casuale (bit 0x02): un telefono vicino, non la sede
    first, _ = send(client, ph, lambda raw: ca.chain(raw), wifi=wifi(*sede, hotspot))
    second, _ = send(client, ph, lambda raw: ca.chain(raw), wifi=wifi(sede[0], "00:aa:bb:cc:dd:ee"))
    d = ph.admin(client, "GET", f"/api/v1/admin/punches/{second['id']}").json()["wifi"]
    assert d["reti"] == 2 and d["gia_viste_in_altre_timbrature"] == 1 and d["scartate_mac_casuali"] == 0
    d1 = ph.admin(client, "GET", f"/api/v1/admin/punches/{first['id']}").json()["wifi"]
    assert d1["reti"] == 2 and d1["scartate_mac_casuali"] == 1
    z = zipfile.ZipFile(io.BytesIO(ph.admin(client, "GET", f"/api/v1/admin/punches/{first['id']}/evidence.zip").content))
    assert json.loads(z.read("record.json"))["reti_wifi"]["gia_viste_in_altre_timbrature"] == 1
    assert "wifi" in json.loads(z.read("manifest.json"))  # nei byte firmati dal telefono


def test_reti_wifi_non_valide(env):
    client, ph, ca = env
    for bad in (wifi("AA:BB:CC:DD:EE:FF"), wifi("00:1a:2b:3c:4d"), wifi("00:1a:2b:3c:4d:5e", rssi=10),
                {"aps": [{"bssid": "00:1a:2b:3c:4d:5e"}] * 65}):
        raw, sig = ph.punch(os.urandom(100), wifi=bad)
        assert client.post("/api/v1/punches", json=body(raw, sig), headers=ph.auth).status_code == 422



def test_chiave_di_attestazione_di_fabbrica(env, tmp_path, capsys):
    """Chiavi installate in fabbrica (serialNumber nell'intermedio sotto la radice): valgono meno di quelle emesse da
    Google, perché per alcuni modelli sono trapelate. La pagina e verifica.py lo dicono."""
    from timbro import verifica_pacchetto as vp

    client, ph, _ = env
    factory = AttestationCA(tmp_path / "radici", factory=True)  # sostituisce la radice di prova prima del primo uso
    st, _ = send(client, ph, lambda raw: factory.chain(raw))
    assert st["attestation"]["ok"]
    r = ph.admin(client, "POST", "/api/v1/admin/shares", {"punch_ids": [st["id"]], "label": "x"})
    page = client.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert "Chiave di attestazione installata in fabbrica: vale meno di una emessa da Google" in page
    assert ", chiave di attestazione emessa da Google" not in page
    z = zipfile.ZipFile(io.BytesIO(ph.admin(client, "GET", f"/api/v1/admin/punches/{st['id']}/evidence.zip").content))
    z.extractall(tmp_path / "p")
    rep = vp.verify_package(tmp_path / "p", online=False)
    out = capsys.readouterr().out
    assert rep.errors == 0, out
    assert "ATTN chiave di attestazione installata in fabbrica" in out

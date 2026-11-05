"""M2: API di gestione firmate, link di condivisione, pagina pubblica, pacchetto di prova, conservazione."""

import hashlib
import io
import json
import os
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sqlalchemy import select, update

from tests.helpers import Phone, body
from timbro import accounts, services
from timbro.models import Device, Punch, ShareLink


def punch(app, phone, video=None, kind="in"):
    video = video or os.urandom(3000)
    raw, sig = phone.punch(video, kind=kind)
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    assert app.put(f"/api/v1/punches/{pid}/video", content=video, headers=phone.auth).status_code == 200
    return pid, video, raw


# --- firma admin -------------------------------------------------------------------------------


def test_admin_richiede_firma(app, phone):
    assert app.get("/api/v1/admin/punches", headers=phone.auth).status_code == 401
    assert phone.admin(app, "GET", "/api/v1/admin/punches").status_code == 200


def test_admin_chiave_sbagliata(app, phone):
    # la chiave delle timbrature (senza biometria) non basta per le operazioni di gestione
    r = phone.admin(app, "GET", "/api/v1/admin/punches", key=phone.key)
    assert r.status_code == 401


def test_admin_replay(app, phone):
    h = phone.admin_headers("GET", "/api/v1/admin/punches")
    assert app.get("/api/v1/admin/punches", headers=h).status_code == 200
    r = app.get("/api/v1/admin/punches", headers=h)
    assert r.status_code == 401 and "replay" in r.text


def test_admin_ora_fuori_tolleranza(app, phone):
    old = (datetime.now(UTC) - timedelta(minutes=30)).isoformat()
    assert phone.admin(app, "GET", "/api/v1/admin/punches", time=old).status_code == 401


def test_admin_corpo_alterato(app, phone):
    pid, _, _ = punch(app, phone)
    url = f"/api/v1/admin/punches/{pid}"
    h = phone.admin_headers("PATCH", url, json.dumps({"keep": True}).encode())
    h["Content-Type"] = "application/json"
    r = app.patch(url, content=json.dumps({"keep": False}).encode(), headers=h)
    assert r.status_code == 401


def test_admin_query_firmata(app, phone):
    # la query fa parte della stringa firmata: cambiarla invalida la firma
    h = phone.admin_headers("GET", "/api/v1/admin/punches?limit=5")
    assert app.get("/api/v1/admin/punches?limit=6", headers=h).status_code == 401


def test_chiave_admin_obbligatoria_e_diversa(app):
    with app.app.state.db.session() as s:
        code = services.new_pairing_code(s, services.create_user(s, "altro"), app.app.state.settings)
    ph = Phone()
    reg = ph.registration(code)
    reg["admin_public_key_pem"] = reg["public_key_pem"]
    assert app.post("/api/v1/devices/register", json=reg).status_code == 422
    del reg["admin_public_key_pem"]
    assert app.post("/api/v1/devices/register", json=reg).status_code == 422


# --- storico e flag ----------------------------------------------------------------------------


def test_storico_flag_e_cancellazione_video(app, phone):
    ids = [punch(app, phone, kind=k)[0] for k in ("in", "out", "in")]
    r = phone.admin(app, "GET", "/api/v1/admin/punches?limit=2").json()
    assert [x["id"] for x in r["items"]] == ids[:0:-1] and r["next_before_id"] == ids[1]

    r = phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[0]}", {"contested": True, "justification_ref": "prot. 123"})
    assert r.json()["contested"] and r.json()["justification_ref"] == "prot. 123"
    assert phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[0]}", {"keep": None}).status_code == 422

    # video contestato: non si cancella senza togliere il flag
    assert phone.admin(app, "DELETE", f"/api/v1/admin/punches/{ids[0]}/video").status_code == 409
    r = phone.admin(app, "DELETE", f"/api/v1/admin/punches/{ids[1]}/video").json()
    assert not r["video_available"] and r["video_deleted_reason"] == "manual"
    # la prova resta: il pacchetto si genera anche senza video
    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{ids[1]}/evidence.zip").content))
    assert "video.mp4" not in z.namelist() and "manifest.json" in z.namelist()


def test_video_con_ticket_e_piu_range(app, phone):
    # un player ripete le richieste Range sullo stesso URL: il ticket lo consente, la firma no
    pid, video, _ = punch(app, phone)
    t = phone.admin(app, "POST", f"/api/v1/admin/punches/{pid}/video-ticket").json()
    for rng, part in (("bytes=0-99", video[:100]), ("bytes=100-199", video[100:200]), ("bytes=0-99", video[:100])):
        r = app.get(t["url"], headers={"Range": rng})
        assert r.status_code == 206 and r.content == part
    assert app.get("/api/v1/media/inventato").status_code == 404


# --- pacchetto di prova ------------------------------------------------------------------------


def test_pacchetto_di_prova(app, phone):
    pid, video, raw = punch(app, phone)
    r = phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip")
    assert r.status_code == 200 and "attachment" in r.headers["content-disposition"]
    z = zipfile.ZipFile(io.BytesIO(r.content))
    assert z.read("video.mp4") == video
    assert z.read("manifest.json") == raw
    # la firma del telefono si verifica con la chiave inclusa nel pacchetto
    key = serialization.load_pem_public_key(z.read("device_pubkey.pem"))
    key.verify(z.read("manifest.sig"), z.read("manifest.json"), ec.ECDSA(hashes.SHA256()))
    rec = json.loads(z.read("record.json"))
    assert rec["timbratura"]["sha256_video"] == hashlib.sha256(video).hexdigest()
    assert rec["catena_fino_alla_testa_del_giorno"][0]["chain_hash"] == rec["timbratura"]["chain_hash"]
    assert "openssl" in z.read("VERIFICA.md").decode()


# --- link di condivisione e pagina pubblica ----------------------------------------------------


def share(app, phone, ids, **kw):
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": ids, "label": "Per l'avvocato", **kw})
    assert r.status_code == 200, r.text
    return r.json()


def test_condivisione_completa(app, phone):
    a, video, _ = punch(app, phone)
    b, _, _ = punch(app, phone, kind="out")
    # b in un altro giorno: il link porta la giornata intera di a, non le altre
    yesterday = datetime.now(UTC) - timedelta(days=1)
    with app.app.state.db.session() as s:
        s.execute(update(Punch).where(Punch.id == b).values(server_received_at=yesterday, device_time=yesterday.isoformat()))
        s.commit()
    sh = share(app, phone, [a])
    path = sh["url"].split("://", 1)[1].split("/", 1)[1]
    page = app.get(f"/{path}")
    assert page.status_code == 200 and "Per l&#39;avvocato" in page.text
    assert "noindex" in page.headers["x-robots-tag"] and page.headers["referrer-policy"] == "no-referrer"
    assert app.get(f"/{path}/p/{a}/video").content == video
    assert app.get(f"/{path}/p/{a}/evidence.zip").status_code == 200
    # una timbratura non inclusa nel link non è raggiungibile
    assert app.get(f"/{path}/p/{b}/video").status_code == 404
    # accessi registrati e visibili all'admin
    acc = phone.admin(app, "GET", f"/api/v1/admin/shares/{sh['id']}/accesses").json()
    assert [x["what"] for x in acc] == ["page", f"video:{a}", f"evidence:{a}"]
    # revoca: il link smette di funzionare
    phone.admin(app, "DELETE", f"/api/v1/admin/shares/{sh['id']}")
    assert app.get(f"/{path}").status_code == 404


def test_condivisione_porta_la_giornata_intera(app, phone):
    a, _, _ = punch(app, phone)
    b, _, _ = punch(app, phone, kind="out")
    sh = share(app, phone, [b])
    assert sh["punch_ids"] == [a, b]  # scelta solo l'uscita, il link mostra anche l'entrata dello stesso giorno
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    assert app.get(f"{path}/p/{a}/video").status_code == 200
    c, _, _ = punch(app, phone)  # le timbrature successive non entrano in un link già creato
    assert app.get(f"{path}/p/{c}/video").status_code == 404


def test_condivisione_scaduta_e_senza_download(app, phone):
    a, _, _ = punch(app, phone)
    sh = share(app, phone, [a], allow_evidence_download=False)
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    assert app.get(f"{path}/p/{a}/evidence.zip").status_code == 403
    with app.app.state.db.session() as s:
        s.execute(update(ShareLink).values(expires_at=datetime.now(UTC) - timedelta(seconds=1)))
        s.commit()
    assert app.get(path).status_code == 404
    assert app.get("/s/token-inventato").status_code == 404


def test_condivisione_limiti(app, phone):
    a, _, _ = punch(app, phone)
    assert phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [a], "label": "x", "days": 365}).status_code == 422
    assert phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [a, 9999], "label": "x"}).status_code == 422
    # il token in chiaro non è nel DB
    sh = share(app, phone, [a])
    token = sh["url"].rsplit("/", 1)[1]
    with app.app.state.db.session() as s:
        assert token not in {x.token_hash for x in s.scalars(select(ShareLink))}


# --- conservazione -----------------------------------------------------------------------------


def test_conservazione(app, phone):
    ids = [punch(app, phone)[0] for _ in range(3)]
    phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[0]}", {"keep": True})
    phone.admin(app, "PATCH", f"/api/v1/admin/punches/{ids[1]}", {"contested": True})
    # la conservazione vale per tutti: la cambia solo l'amministratore
    assert phone.admin(app, "PATCH", "/api/v1/admin/system/settings", {"retention_days": 30}).status_code == 403
    with app.app.state.db.session() as s:
        accounts.set_role(s, s.get(Device, phone.device_id).user_id, "admin")
    assert phone.admin(app, "PATCH", "/api/v1/admin/system/settings", {"retention_days": 3}).status_code == 422
    assert phone.admin(app, "PATCH", "/api/v1/admin/system/settings", {"retention_days": 30}).json()["retention_days"] == 30
    assert phone.admin(app, "GET", "/api/v1/admin/settings").json()["retention_days"] == 30
    db, settings = app.app.state.db, app.app.state.settings
    with db.session() as s:
        assert services.retention_sweep(s, settings, datetime.now(UTC) + timedelta(days=29)) == 0
        assert services.retention_sweep(s, settings, datetime.now(UTC) + timedelta(days=31)) == 1
        rows = {p.id: p for p in s.scalars(select(Punch))}
    assert rows[ids[0]].video_deleted_at is None  # da tenere
    assert rows[ids[1]].video_deleted_at is None  # contestata
    assert rows[ids[2]].video_deleted_reason == "retention"
    assert not (settings.videos_dir / rows[ids[2]].video_path).exists()
    assert rows[ids[2]].chain_hash  # la prova resta


# --- review post-M2 -------------------------------------------------------------------------------


def test_zip_senza_range_e_senza_file_temporanei(app, phone, tmp_path):
    import tempfile

    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    before = set(Path(tempfile.gettempdir()).glob("timbro-*.zip"))
    for rng in ("bytes=99999999-", "bytes=zzz", None):
        r = app.get(f"{path}/p/{pid}/evidence.zip", headers={"Range": rng} if rng else {})
        assert r.status_code == 200 and r.headers["accept-ranges"] == "none"
    assert set(Path(tempfile.gettempdir()).glob("timbro-*.zip")) == before
    cache = app.app.state.settings.data_dir / "evidence-cache"
    assert len(list(cache.glob("*.zip"))) == 1 and not list(cache.glob("*.part"))


def test_cancellazione_video_svuota_la_cache(app, phone):
    pid, _, _ = punch(app, phone)
    phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip")
    cache = app.app.state.settings.data_dir / "evidence-cache"
    assert list(cache.glob(f"{pid}-*.zip"))
    phone.admin(app, "DELETE", f"/api/v1/admin/punches/{pid}/video")
    assert not list(cache.glob(f"{pid}-*.zip"))  # nessuna copia del video sopravvive alla cancellazione


def test_conservazione_rispetta_flag_messo_nel_frattempo(app, phone):
    pid, _, _ = punch(app, phone)
    db, settings = app.app.state.db, app.app.state.settings
    with db.session() as s:
        # la conservazione ha già selezionato il video, poi arriva il flag "contestata"
        s.execute(update(Punch).where(Punch.id == pid).values(contested=True))
        s.commit()
        assert services.delete_video(s, settings, pid, "retention") is False
        p = s.get(Punch, pid)
        assert p.video_deleted_at is None and (settings.videos_dir / p.video_path).exists()


def test_cancellazione_prima_che_arrivi_il_video(app, phone):
    raw, sig = phone.punch(os.urandom(100))
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    r = phone.admin(app, "DELETE", f"/api/v1/admin/punches/{pid}/video")
    assert r.status_code == 409 and "non è ancora arrivato" in r.text


def test_revoca_telefono(app, phone):
    pid, video, _ = punch(app, phone)
    kept = share(app, phone, [pid])
    with app.app.state.db.session() as s:
        _, n = services.revoke_device(s, phone.device_id)
    assert n == 1
    raw, sig = phone.punch(os.urandom(10))
    assert app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).status_code == 401
    assert app.get(f"/api/v1/punches/{pid}", headers=phone.auth).status_code == 401
    assert app.put(f"/api/v1/punches/{pid}/video", content=video, headers=phone.auth).status_code == 401
    assert phone.admin(app, "GET", "/api/v1/admin/punches").status_code == 401
    # i link creati da quel telefono sono revocati
    path = "/" + kept["url"].split("://", 1)[1].split("/", 1)[1]
    assert app.get(path).status_code == 404


def test_revoca_telefono_tenendo_i_link(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    with app.app.state.db.session() as s:
        _, n = services.revoke_device(s, phone.device_id, keep_shares=True)
    assert n == 0
    assert app.get("/" + sh["url"].split("://", 1)[1].split("/", 1)[1]).status_code == 200


def test_senza_download_il_video_resta_solo_in_visione(app, phone):
    pid, video, _ = punch(app, phone)
    sh = share(app, phone, [pid], allow_evidence_download=False)
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    r = app.get(f"{path}/p/{pid}/video")
    assert r.headers["content-disposition"] == "inline"
    assert "nodownload" in app.get(path).text and "evidence.zip" not in app.get(path).text


def test_storico_date_con_fuso(app, phone):
    punch(app, phone)
    assert phone.admin(app, "GET", "/api/v1/admin/punches?since=2026-01-01T00:00:00").status_code == 422
    r = phone.admin(app, "GET", "/api/v1/admin/punches?since=2020-01-01T00:00:00%2B02:00")
    assert r.status_code == 200 and len(r.json()["items"]) == 1


def test_intestazioni_di_sicurezza_anche_sugli_errori(app):
    r = app.get("/s/non-esiste")
    assert r.status_code == 404
    for h in ("x-robots-tag", "content-security-policy", "cache-control", "x-content-type-options"):
        assert h in r.headers
    assert "base-uri 'none'" in r.headers["content-security-policy"]
    r = app.get("/api/v1/punches/1")
    assert r.status_code == 401 and r.headers["x-content-type-options"] == "nosniff"


def test_limite_di_frequenza_sui_link(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    codes = [app.get(path).status_code for _ in range(app.app.state.settings.share_rate_limit_per_min + 1)]
    assert codes[-1] == 429 and set(codes[:-1]) == {200}


def test_accessi_video_non_moltiplicati_dal_player(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    for rng in ("bytes=0-", "bytes=100-199", "bytes=200-299"):
        app.get(f"{path}/p/{pid}/video", headers={"Range": rng})
    acc = phone.admin(app, "GET", f"/api/v1/admin/shares/{sh['id']}/accesses").json()
    assert [a["what"] for a in acc] == [f"video:{pid}"]


def test_token_oscurati_nei_log():
    import logging

    from timbro.log_setup import RedactingFormatter

    fmt = RedactingFormatter("%(message)s")
    rec = logging.LogRecord("uvicorn.error", logging.INFO, "", 0, '%s - "%s %s HTTP/1.1" %d', ("1.2.3.4", "GET", "/s/SEGRETO123/p/1/video", 200), None)
    out = fmt.format(rec)
    assert "SEGRETO123" not in out and "/s/***/p/1/video" in out
    rec = logging.LogRecord("x", logging.ERROR, "", 0, "ticket /api/v1/media/TICKET456 e db postgresql+psycopg://timbro:PASSWORD789@postgres:5432/timbro", None, None)
    out = fmt.format(rec)
    assert "TICKET456" not in out and "PASSWORD789" not in out and "timbro:<oscurato>@postgres" in out


def test_script_di_verifica_nel_pacchetto(app, phone, tmp_path):
    import subprocess
    import sys

    pid, _, _ = punch(app, phone)
    punch(app, phone, kind="out")  # un secondo anello nello stesso giorno
    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip").content))
    z.extractall(tmp_path)
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path, capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
    assert "TUTTO VERIFICATO" in out.stdout
    # alterare un'ora di ricezione nel record fa fallire il ricalcolo della catena
    rec = json.loads((tmp_path / "record.json").read_text(encoding="utf-8"))
    rec["catena_fino_alla_testa_del_giorno"][1]["server_received_at"] = "2000-01-01T00:00:00+00:00"
    (tmp_path / "record.json").write_text(json.dumps(rec), encoding="utf-8")
    assert subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path, capture_output=True).returncode == 1

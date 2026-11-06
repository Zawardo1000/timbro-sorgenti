"""Correzioni della review del 05/11/2026: un test per ognuna."""

import os
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import select, text, update

from tests.helpers import Phone, body
from tests.test_admin import punch, share
from timbro import cli, problems, services
from timbro.app import create_app
from timbro.config import Settings
from timbro.db import Database, utcnow
from timbro.models import Punch, ShareAccess, Stamp
from timbro.worker import Worker


def settings_for(database_url, tmp_path, **kw) -> Settings:
    return Settings(database_url=database_url, data_dir=tmp_path, worker_enabled=False, _env_file=None, **kw)


# --- configurazione e avvio ---------------------------------------------------------------------------------------


def test_provider_tsa_sbagliati_fermano_l_avvio(database_url, tmp_path):
    with pytest.raises(ValidationError, match="sconosciuti"):
        settings_for(database_url, tmp_path, tsa_providers=["accv", "acvv"])
    with pytest.raises(ValidationError, match="almeno una TSA qualificata"):
        settings_for(database_url, tmp_path, tsa_providers=["freetsa", "digicert"])
    with pytest.raises(ValidationError, match="ripetuti"):
        settings_for(database_url, tmp_path, record_tsa_providers=["digicert", "digicert"])


def test_schema_piu_nuovo_del_codice(database_url, tmp_path, monkeypatch):
    """Dopo un ritorno alla versione precedente (migrazione già applicata) l'app parte e lo segnala."""
    db = Database(database_url)
    assert db.migrate() == "aggiornato"
    with db.engine.begin() as c:
        c.execute(text("UPDATE alembic_version SET version_num = 'ffffffffffff'"))
    assert db.migrate() == "piu-nuovo" and db.schema_state() == "piu-nuovo"
    db.dispose()
    with TestClient(create_app(settings_for(database_url, tmp_path))) as client:
        assert client.get("/health").status_code == 200
        assert "schema" in {p["chiave"] for p in client.get("/internal/problemi").json()["problemi"]}
    # la CLI lavora lo stesso (avvisa), ma non migra se non con "migrate"
    monkeypatch.setenv("DATABASE_URL", database_url)
    cli.get_settings.cache_clear()
    try:
        assert cli.main(["list-devices"]) == 0
    finally:
        cli.get_settings.cache_clear()


def test_cli_non_migra_e_si_ferma_se_lo_schema_e_indietro(database_url, monkeypatch, capsys):
    monkeypatch.setenv("DATABASE_URL", database_url)
    cli.get_settings.cache_clear()
    try:
        assert cli.main(["list-devices"]) == 1  # database vuoto: niente migrazioni di nascosto
        assert "non aggiornato" in capsys.readouterr().err
        assert cli.main(["migrate"]) == 0
        assert cli.main(["list-devices"]) == 0
    finally:
        cli.get_settings.cache_clear()


def test_lock_d_istanza_ripreso_dopo_la_caduta_della_connessione(app):
    db = app.app.state.db
    assert db.check_instance_lock()
    pid = db._instance_conn.scalar(text("SELECT pg_backend_pid()"))
    db._instance_conn.commit()
    with db.engine.connect() as c:
        c.execute(text("SELECT pg_terminate_backend(:p)"), {"p": pid})
        c.commit()
    assert db.check_instance_lock()  # connessione caduta: lock ripreso su una nuova


# --- worker --------------------------------------------------------------------------------------------------------


def test_una_fase_che_fallisce_non_ferma_le_marche(app, phone, monkeypatch):
    pid, _, _ = punch(app, phone)
    w = Worker(app.app.state.db, app.app.state.settings.model_copy(update={"periodic_checks_enabled": False}))
    monkeypatch.setattr(w, "retention", lambda now: 1 / 0)
    from timbro.stamping import tsa

    monkeypatch.setattr(tsa, "request_stamp", lambda p, r, c: (_ for _ in ()).throw(tsa.StampError("rete giù")))
    monkeypatch.setattr("timbro.stamping.ots.stamp", lambda d, c: (_ for _ in ()).throw(RuntimeError("no")))
    w.run_once(wait=True)
    assert "conservazione" in w.failing and "ZeroDivisionError" in w.failing["conservazione"][1]
    with app.app.state.db.session() as s:
        attempts = set(s.scalars(select(Stamp.attempts).where(Stamp.punch_id == pid)))
    assert attempts == {1}  # le marche sono state tentate lo stesso
    state = problems.AppState(worker_last_loop=utcnow(), worker_failing=w.failing)
    with app.app.state.db.session() as s:
        keys = {p.key for p in problems.find(s, app.app.state.settings, utcnow() + timedelta(minutes=11), state)}
    assert "worker-conservazione" in keys
    w.stop()


def test_marca_non_sovrascritta_se_cambiata_durante_la_rete(app, phone, monkeypatch):
    pid, _, _ = punch(app, phone)
    db = app.app.state.db
    with db.session() as s:
        st_id = s.scalar(select(Stamp.id).where(Stamp.punch_id == pid, Stamp.provider == "accv"))
    from timbro.stamping import tsa

    def slow(provider, req, client):
        with db.session() as s:  # mentre la richiesta è in volo qualcuno cambia lo stato della marca
            s.execute(update(Stamp).where(Stamp.id == st_id).values(status="failed"))
            s.commit()
        raise tsa.StampError("timeout")

    monkeypatch.setattr(tsa, "request_stamp", slow)
    Worker(db, app.app.state.settings).process_stamp(st_id)
    with db.session() as s:
        st = s.get(Stamp, st_id)
    assert st.status == "failed" and st.attempts == 0  # l'esito vecchio non ha sovrascritto il cambio


# --- conservazione e file ------------------------------------------------------------------------------------------


def test_conservazione_rispetta_i_link_attivi(app, phone):
    a, _, _ = punch(app, phone)
    b, _, _ = punch(app, phone)
    yesterday = datetime.now(UTC) - timedelta(days=1)  # b in un altro giorno: il link porta solo la giornata di a
    with app.app.state.db.session() as s:
        s.execute(update(Punch).where(Punch.id == b).values(server_received_at=yesterday, device_time=yesterday.isoformat()))
        s.commit()
    sh = share(app, phone, [a])
    settings = app.app.state.settings
    later = datetime.now(UTC) + timedelta(days=200)
    with app.app.state.db.session() as s:
        s.execute(text("UPDATE punches SET server_received_at = server_received_at - interval '100 days'"))
        s.commit()
    with app.app.state.db.session() as s:
        assert services.retention_sweep(s, settings, datetime.now(UTC)) == 1  # solo b: a è in un link valido
    phone.admin(app, "DELETE", f"/api/v1/admin/shares/{sh['id']}")
    with app.app.state.db.session() as s:
        assert services.retention_sweep(s, settings, later) == 1  # link revocato: ora anche a


def test_pulizia_dei_file_rimasti(app, phone):
    settings = app.app.state.settings
    pid, _, _ = punch(app, phone)
    with app.app.state.db.session() as s:
        p = s.get(Punch, pid)
        path = settings.videos_dir / p.video_path
        # processo ucciso fra il commit della cancellazione e la cancellazione del file
        s.execute(update(Punch).where(Punch.id == pid).values(video_deleted_at=utcnow(), video_deleted_reason="manual"))
        s.commit()
    old_part = path.parent / "9.abc.part"
    new_part = path.parent / "10.def.part"
    for f in (old_part, new_part):
        f.write_bytes(b"x")
    two_days_ago = (datetime.now() - timedelta(days=2)).timestamp()
    os.utime(old_part, (two_days_ago, two_days_ago))
    with app.app.state.db.session() as s:
        assert services.cleanup_files(s, settings) == 2
    assert not path.exists() and not old_part.exists() and new_part.exists()
    assert services.cleanup_partial_uploads(settings) == 1  # all'avvio: tutti


def test_cache_dei_pacchetti_esclusa_dal_backup(app, phone):
    pid, _, _ = punch(app, phone)
    assert phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip").status_code == 200
    tag = app.app.state.settings.data_dir / "evidence-cache" / "CACHEDIR.TAG"
    assert tag.read_text().startswith("Signature: 8a477f597d28d172789f06886806bc55")


# --- verifica dell'archivio ----------------------------------------------------------------------------------------


def test_verifica_controlla_i_file_video(app, phone):
    from timbro import checks

    settings = app.app.state.settings
    pid, _, _ = punch(app, phone)
    assert checks.run_archive(app.app.state.db, settings)["ok"]
    with app.app.state.db.session() as s:
        path = settings.videos_dir / s.get(Punch, pid).video_path
    path.write_bytes(b"alterato")
    rec = checks.run_archive(app.app.state.db, settings)
    assert not rec["ok"] and any("impronta del manifest" in e for e in rec["errors"])
    path.unlink()
    rec = checks.run_archive(app.app.state.db, settings)
    assert any("file mancante" in e for e in rec["errors"])


def test_verifica_con_piu_di_un_blocco_di_marche(app):
    """Oltre 100 marche RFC 3161 completate la lettura passa al blocco successivo: prima si svuotava la sessione a
    metà lettura e la verifica falliva con InvalidRequestError (06/11/2026)."""
    from datetime import date

    from timbro import checks
    from timbro.models import Stamp

    db = app.app.state.db
    with db.session() as s:
        for i in range(250):
            s.add(Stamp(
                target="chain", chain_day=date(2020, 1, 1) + timedelta(days=i), provider="accv", kind="rfc3161",
                qualified=True, digest="00" * 32, status="complete", token=b"x",
            ))
        s.commit()
    rec = checks.run_archive(db, app.app.state.settings)
    assert rec["error"] is None
    assert rec["stamps_checked"] == 250
    assert not rec["ok"]  # token finti: ogni marca dà errore, ma la verifica arriva in fondo


def test_verifica_salva_l_esito_anche_se_fallisce(app, monkeypatch):
    from timbro import checks

    monkeypatch.setattr(checks, "verify_archive", lambda *a, **kw: 1 / 0)
    rec = checks.run_archive(app.app.state.db, app.app.state.settings)
    assert rec["report"] is None and "ZeroDivisionError" in rec["error"] and rec["started_at"]


# --- API ------------------------------------------------------------------------------------------------------------


def test_il_token_del_telefono_vede_solo_le_sue_timbrature(app, phone):
    pid, _, _ = punch(app, phone)
    with app.app.state.db.session() as s:
        user = s.get(Punch, pid).user_id
        from timbro.models import User

        code = services.new_pairing_code(s, s.get(User, user), app.app.state.settings)
    other = Phone()
    r = app.post("/api/v1/devices/register", json=other.registration(code, "secondo")).json()
    other.device_id, other.token = r["device_id"], r["api_token"]
    assert app.get(f"/api/v1/punches/{pid}", headers=other.auth).status_code == 404
    assert app.get(f"/api/v1/punches/{pid}", headers=phone.auth).status_code == 200


def test_chiavi_salvate_in_forma_canonica(app):
    db = app.app.state.db
    with db.session() as s:
        code = services.new_pairing_code(s, services.create_user(s, "altro"), app.app.state.settings)
    ph = Phone()
    compressed = ph.key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.CompressedPoint)
    # stessa chiave in uno SPKI con il punto compresso, scritto a mano: id-ecPublicKey + prime256v1, BIT STRING di 33 byte
    der = bytes.fromhex("3039301306072a8648ce3d020106082a8648ce3d030107032200") + compressed
    import base64

    b64 = base64.b64encode(der).decode()
    reg = ph.registration(code)
    reg["public_key_pem"] = f"-----BEGIN PUBLIC KEY-----\n{b64}\n-----END PUBLIC KEY-----\n"
    assert reg["public_key_pem"] != ph.pem
    r = app.post("/api/v1/devices/register", json=reg)
    assert r.status_code == 200
    from timbro.models import Device

    with db.session() as s:
        d = s.get(Device, r.json()["device_id"])
    assert d.public_key_pem == ph.pem  # forma canonica: quella che entra nella catena e nel pacchetto


def test_firma_admin_legata_a_percorso_e_query_grezzi(app, phone):
    pid, _, _ = punch(app, phone)
    url = "/api/v1/admin/punches?limit=1"
    assert phone.admin(app, "GET", url).status_code == 200
    # firma per una query, richiesta con un'altra: rifiutata
    h = phone.admin_headers("GET", url)
    assert app.get("/api/v1/admin/punches?limit=1&contested=true", headers=h).status_code == 401


def test_input_che_davano_500(app, phone):
    pid, _, _ = punch(app, phone)
    r = phone.admin(app, "GET", "/api/v1/admin/punches?since=0001-01-01T00:00:00%2B01:00")
    assert r.status_code == 422
    r = phone.admin(app, "PATCH", f"/api/v1/admin/punches/{pid}", {"notes": "a\x00b"})
    assert r.status_code == 422
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [pid], "label": "x\x00"})
    assert r.status_code == 422
    raw, sig = phone.punch(os.urandom(100), duration_ms=2**31)
    assert app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).status_code in (400, 422)
    raw, sig = phone.punch(os.urandom(100), app_version="1\x00")
    assert app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).status_code in (400, 422)


# --- pagina pubblica -----------------------------------------------------------------------------------------------


def _path(sh) -> str:
    return "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]


def test_registro_accessi_senza_doppioni_e_paginato(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    for _ in range(5):
        assert app.get(_path(sh)).status_code == 200
        assert app.get(f"{_path(sh)}/p/{pid}/video", headers={"Range": "bytes=100-"}).status_code == 206
    acc = phone.admin(app, "GET", f"/api/v1/admin/shares/{sh['id']}/accesses").json()
    assert sorted(a["what"] for a in acc) == ["page", f"video:{pid}"]  # una riga per cosa e IP nella finestra
    page = phone.admin(app, "GET", f"/api/v1/admin/shares/{sh['id']}/accesses?after_id={acc[0]['id']}&limit=1").json()
    assert [a["id"] for a in page] == [acc[1]["id"]]
    revoked = phone.admin(app, "DELETE", f"/api/v1/admin/shares/{sh['id']}").json()
    assert revoked["accesses"] == 2


def test_spostamenti_nel_video_non_consumano_il_limite(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    app.app.state.share_limiter.limit = 3
    for _ in range(10):  # richieste Range a metà file: il player che si sposta
        assert app.get(f"{_path(sh)}/p/{pid}/video", headers={"Range": "bytes=10-"}).status_code == 206
    assert app.get(_path(sh)).status_code == 200


def test_token_di_forma_impossibile(app):
    assert app.get("/s/corto").status_code == 404
    assert app.get("/s/" + "a" * 43).status_code == 404  # forma giusta, link inesistente
    with app.app.state.db.session() as s:
        assert s.scalar(select(ShareAccess.id)) is None


def test_player_senza_precaricamento(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    html = app.get(_path(sh)).text
    assert 'preload="none"' in html and 'preload="metadata"' not in html


def test_pacchetto_ricostruito_se_il_video_sparisce_durante_la_costruzione(app, phone, monkeypatch):
    from timbro import evidence

    pid, video, _ = punch(app, phone)
    settings = app.app.state.settings
    real_write = evidence.zipfile.ZipFile.write

    def write_then_delete(self, filename, arcname=None, *a, **kw):
        real_write(self, filename, arcname, *a, **kw)
        if arcname == "video.mp4":  # il video viene cancellato mentre lo zip si sta scrivendo
            with app.app.state.db.session() as s:
                assert services.delete_video(s, settings, pid, "manual")

    monkeypatch.setattr(evidence.zipfile.ZipFile, "write", write_then_delete)
    with app.app.state.db.session() as s:
        path = evidence.build(s, pid, settings)
    import zipfile

    assert "video.mp4" not in zipfile.ZipFile(path).namelist()
    assert all("video.mp4" not in zipfile.ZipFile(f).namelist() for f in path.parent.glob("*.zip"))


def test_hash_della_versione_dipende_dal_contenuto_della_trusted_list(app, phone, tmp_path):
    from timbro import evidence

    pid, _, _ = punch(app, phone)
    settings = app.app.state.settings
    d = settings.data_dir / "trusted-lists"
    d.mkdir(parents=True, exist_ok=True)
    tl = d / "2026-11-05_ES_190.xml"
    tl.write_bytes(b"<a/>")
    with app.app.state.db.session() as s:
        first = evidence.build(s, pid, settings)
    tl.write_bytes(b"<b/>")  # stesso nome, contenuto diverso (es. una copia troncata rifatta)
    with app.app.state.db.session() as s:
        second = evidence.build(s, pid, settings)
    assert first != second


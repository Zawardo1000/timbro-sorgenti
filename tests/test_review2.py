"""Correzioni della seconda review (05/11/2026): attestazione, verifica dell'archivio, link pubblici."""

import base64
import hashlib
import json
from datetime import UTC, datetime, timedelta

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from sqlalchemy import select, update

from tests.helpers import AttestationCA, key_description
from tests.test_admin import punch, share
from tests.test_attestazione import env, problem_keys, send  # noqa: F401 (fixture)
from timbro import attestation, checks, services
from timbro.models import Punch, Stamp
from timbro.verify import Report
from timbro.worker import Worker

PKG, DIGEST = "it.test.timbro", ["11" * 32]
RAW = b'{"manifest": "di prova"}'


def der(cert) -> bytes:
    return cert.public_bytes(serialization.Encoding.DER)


def decoded(chain: list[str]) -> list[bytes]:
    return [base64.b64decode(c) for c in chain]


def check(ca: AttestationCA, chain_der, roots_dir, now=None, **kw):
    roots = attestation.load_roots(roots_dir)
    kw = {"package": PKG, "signing_cert_sha256": DIGEST, **kw}
    return attestation.verify(chain_der, RAW, roots, now or datetime.now(UTC), **kw)


def fake_cert(pub, signer, ext=None, ca=False, issuer="chiunque"):
    b = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "foglia inventata")]))
        .issuer_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, issuer)]))
        .public_key(pub)
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime(1970, 1, 1, tzinfo=UTC))
        .not_valid_after(datetime(2048, 1, 1, tzinfo=UTC))
        .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
    )
    if ext is not None:
        b = b.add_extension(x509.UnrecognizedExtension(x509.ObjectIdentifier(attestation.KEY_DESCRIPTION_OID), ext), critical=False)
    return b.sign(signer, hashes.SHA256())


@pytest.fixture
def ca(tmp_path):
    return AttestationCA(tmp_path / "radici")


def test_catena_valida_di_riferimento(ca, tmp_path):
    r = check(ca, decoded(ca.chain(RAW)), tmp_path / "radici")
    assert r.ok, r.problems
    assert r.provisioning == "remota" and r.root


# --- falle critiche: catene costruite da un attaccante -------------------------------------------------------------


def test_radice_usata_come_foglia(ca, tmp_path):
    """Certificato con la chiave pubblica della radice, firmato da chiunque: nessuna firma veniva controllata."""
    kd = key_description(hashlib.sha256(RAW).digest(), level=2)
    forged = fake_cert(ca.root_key.public_key(), ec.generate_private_key(ec.SECP256R1()), ext=kd)
    for chain in ([der(forged)], [der(forged), der(ca.root)]):
        r = check(ca, chain, tmp_path / "radici")
        assert not r.ok
        assert any("foglia è una radice" in p for p in r.problems), r.problems


def test_catena_estesa_con_una_foglia_inventata(ca, tmp_path):
    """Una chiave attestata qualsiasi (telefono sbloccato, app qualsiasi) firma una foglia inventata con un'attestazione
    perfetta: la KeyDescription vera sta nel secondo certificato, che non è un'autorità."""
    real = decoded(ca.chain(b"altro", locked=False, boot=2, package="com.qualsiasi"))
    forged = fake_cert(ec.generate_private_key(ec.SECP256R1()).public_key(), ca.leaf_key,
                       ext=key_description(hashlib.sha256(RAW).digest(), level=2))
    r = check(ca, [der(forged), *real], tmp_path / "radici")
    assert not r.ok
    joined = " ".join(r.problems)
    assert "catena estesa" in joined and "non è un'autorità" in joined


def test_catena_troppo_corta(ca, tmp_path):
    leaf = fake_cert(ec.generate_private_key(ec.SECP256R1()).public_key(), ca.root_key,
                     ext=key_description(hashlib.sha256(RAW).digest()))
    r = check(ca, [der(leaf), der(ca.root)], tmp_path / "radici")
    assert not r.ok and any("troppo corta" in p for p in r.problems)


def test_configurazione_vuota_non_e_mai_ok(ca, tmp_path):
    r = check(ca, decoded(ca.chain(RAW)), tmp_path / "radici", package="", signing_cert_sha256=[])
    assert not r.ok
    assert any("ATTESTATION_PACKAGE" in p for p in r.problems)
    assert any("ATTESTATION_SIGNING_CERT_SHA256" in p for p in r.problems)


def test_livelli_di_sicurezza_diversi(ca, tmp_path):
    r = check(ca, decoded(ca.chain(RAW, level=2, keymint_level=1)), tmp_path / "radici")
    assert not r.ok and any("livelli di sicurezza diversi" in p for p in r.problems)


def test_seriale_gia_revocato_alla_timbratura(ca, tmp_path):
    chain = decoded(ca.chain(RAW))
    serial = attestation.Cert(chain[1]).serial
    r = check(ca, chain, tmp_path / "radici", revoked_serials={serial})
    assert not r.ok and r.revoked == [serial]


def test_interi_enormi_e_eccezioni_non_escono(ca, tmp_path, monkeypatch):
    r = check(ca, decoded(ca.chain(RAW, version=10**30)), tmp_path / "radici")
    assert not r.ok and any("fuori misura" in p for p in r.problems)
    json.dumps(r.as_dict())
    # date senza fuso (codifica non canonica): diventano UTC
    assert attestation._aware(datetime(2030, 1, 1)).tzinfo is UTC
    # qualunque errore imprevisto diventa un problema, non un 500
    monkeypatch.setattr(attestation, "_verify", lambda *a: 1 / 0)
    r = check(ca, decoded(ca.chain(RAW)), tmp_path / "radici")
    assert not r.ok and "ZeroDivisionError" in r.problems[0]


# --- date degli intermedi ------------------------------------------------------------------------------------------


def test_scadenza_conta_solo_per_le_catene_rkp(tmp_path):
    later = datetime.now(UTC) + timedelta(days=40)  # intermedio valido fino a +30 giorni
    rkp = AttestationCA(tmp_path / "rkp")
    r = check(rkp, decoded(rkp.chain(RAW)), tmp_path / "rkp", now=later)
    assert any("fuori validità" in p for p in r.problems)
    # timbratura rimasta in coda sul telefono: valida all'ora del manifest, se recente
    r = check(rkp, decoded(rkp.chain(RAW)), tmp_path / "rkp", now=later, punch_time=later - timedelta(days=11))
    assert r.ok, r.problems
    assert r.notes
    r = check(rkp, decoded(rkp.chain(RAW)), tmp_path / "rkp", now=later, punch_time=later - timedelta(days=20))
    assert not r.ok  # fuori dalla finestra di due settimane

    factory = AttestationCA(tmp_path / "fabbrica", factory=True)
    r = check(factory, decoded(factory.chain(RAW)), tmp_path / "fabbrica", now=later)
    assert r.ok, r.problems
    assert r.provisioning == "fabbrica"
    # "non ancora valido" conta sempre
    early = AttestationCA(tmp_path / "presto", factory=True, inter_days=(5, 30))
    r = check(early, decoded(early.chain(RAW)), tmp_path / "presto")
    assert any("fuori validità" in p for p in r.problems)


def test_catena_reale_di_fabbrica_scaduta_nel_2028():
    from tests.test_attestazione import FIXTURES
    from pathlib import Path

    chain = [(FIXTURES / "ec_tee" / f"cert{i}.der").read_bytes() for i in range(4)]
    roots = attestation.load_roots(Path(attestation.__file__).parent / "attestation_roots")
    r = attestation.verify(chain, b"x", roots, datetime(2028, 4, 1, tzinfo=UTC))
    assert r.provisioning == "fabbrica"
    assert not any("validità" in p or "catena" in p for p in r.problems), r.problems


# --- revoche ---------------------------------------------------------------------------------------------------------


class _Resp:
    def __init__(self, data):
        self.data = data

    def raise_for_status(self):
        pass

    def json(self):
        return self.data


def _fake_client(data):
    class C:
        def __init__(self, *a, **kw):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get(self, url):
            return _Resp(data)

    return C


def test_lista_di_revoca_usata_per_le_timbrature_in_arrivo(env, monkeypatch):  # noqa: F811
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw))
    assert st["attestation"]["ok"]
    serial = format(ca.inter.serial_number, "x")
    monkeypatch.setattr(checks.httpx, "Client", _fake_client({"entries": {serial: {"status": "REVOKED"}}}))
    rec = checks.run_attestation_revocations(client.app.state.db, client.app.state.settings)
    assert rec["revoked_serials"] == [serial] and rec["revoked"][0]["punch_id"] == st["id"]
    keys = problem_keys(client)
    assert any(k.startswith("attestazione-revocata:") for k in keys)
    st2, _ = send(client, ph, lambda raw: ca.chain(raw))  # stesso intermedio, ormai revocato
    assert not st2["attestation"]["ok"]
    assert any("revocata" in p for p in st2["attestation"]["problems"])
    # nel pacchetto: cosa risultava all'esportazione
    import io
    import zipfile

    z = zipfile.ZipFile(io.BytesIO(ph.admin(client, "GET", f"/api/v1/admin/punches/{st['id']}/evidence.zip").content))
    rec_json = json.loads(z.read("record.json"))
    assert rec_json["attestazione"]["lista_di_revoca_google"]["revocati"][0]["serial"] == serial


def test_risposta_senza_entries_e_un_errore(env, monkeypatch):  # noqa: F811
    client, ph, ca = env
    monkeypatch.setattr(checks.httpx, "Client", _fake_client({"altro": 1}))
    rec = checks.run_attestation_revocations(client.app.state.db, client.app.state.settings)
    assert rec["error"] and "entries" in rec["error"]


# --- archivio e pacchetto ------------------------------------------------------------------------------------------


def test_archivio_integro_con_le_marche_dell_attestazione(env):  # noqa: F811
    client, ph, ca = env
    send(client, ph, lambda raw: ca.chain(raw))
    rec = checks.run_archive(client.app.state.db, client.app.state.settings)
    assert rec["ok"], rec["errors"]
    with client.app.state.db.session() as s:
        s.execute(update(Stamp).where(Stamp.target == "attest").values(digest="00" * 32))
        s.commit()
    rec = checks.run_archive(client.app.state.db, client.app.state.settings)
    assert not rec["ok"] and any("attest" in e for e in rec["errors"])


def test_video_cancellato_durante_la_verifica(app, phone, monkeypatch):
    pid, _, _ = punch(app, phone)
    db, settings = app.app.state.db, app.app.state.settings

    def snapshot_then_deleted(s, today=None, videos_dir=None):
        with db.session() as s2:  # cancellato dalla conservazione dopo l'istante fotografato
            s2.execute(update(Punch).where(Punch.id == pid).values(video_deleted_at=datetime.now(UTC)))
            s2.commit()
        return Report(missing_videos=[pid])

    monkeypatch.setattr(checks, "verify_archive", snapshot_then_deleted)
    assert checks.run_archive(db, settings)["ok"]
    with db.session() as s:
        s.execute(update(Punch).values(video_deleted_at=None))
        s.commit()
    # ancora conservato per il database: è un errore vero
    monkeypatch.setattr(checks, "verify_archive", lambda s, today=None, videos_dir=None: Report(missing_videos=[pid]))
    rec = checks.run_archive(db, settings)
    assert not rec["ok"] and "file mancante" in rec["errors"][0]


def test_verifica_interrotta_non_resta_a_meta(app, monkeypatch):
    def interrupted(*a, **kw):
        raise KeyboardInterrupt

    monkeypatch.setattr(checks, "verify_archive", interrupted)
    with pytest.raises(KeyboardInterrupt):
        checks.run_archive(app.app.state.db, app.app.state.settings)
    with app.app.state.db.session() as s:
        rec = services.get_json_setting(s, checks.ARCHIVE_KEY)
    assert rec["error"].startswith("KeyboardInterrupt") and rec["started_at"] <= rec["at"]


def test_verifica_mai_conclusa_non_riparte_a_raffica(app, monkeypatch):
    settings = app.app.state.settings.model_copy(update={"periodic_checks_enabled": True})
    db = app.app.state.db
    now = datetime(2026, 11, 5, 9, 0, tzinfo=UTC)
    with db.session() as s:
        services.set_json_setting(s, checks.TL_KEY, {"at": now.isoformat(), "error": None})
        services.set_json_setting(s, checks.REVOCATION_KEY, {"at": now.isoformat(), "error": None})
        services.set_json_setting(s, checks.ARCHIVE_KEY, {
            "at": (now - timedelta(hours=6)).isoformat(), "error": "OperationalError: x",
            "started_at": (now - timedelta(hours=5)).isoformat(),  # ripartita e mai finita (processo ucciso)
        })
    calls = []
    w = Worker(db, settings)
    monkeypatch.setattr(w, "_submit_check", lambda name, fn: calls.append(name))
    w.periodic_checks(now)
    assert "archive" not in calls
    w.stop()


def test_pacchetto_verifica_py_con_telefono_sbloccato(env, tmp_path):  # noqa: F811
    """Telefono sbloccato: il pacchetto è integro (codice 0), le righe ATTN dicono cosa non va del telefono."""
    import io
    import subprocess
    import sys
    import zipfile

    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw, locked=False, boot=2))
    z = zipfile.ZipFile(io.BytesIO(ph.admin(client, "GET", f"/api/v1/admin/punches/{st['id']}/evidence.zip").content))
    z.extractall(tmp_path / "p")
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path / "p", capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
    assert "ATTN bootloader" in out.stdout and "TUTTO VERIFICATO" in out.stdout
    # foglia.pem diversa dalla catena: il pacchetto non è integro, niente traceback
    leaf = fake_cert(ec.generate_private_key(ec.SECP256R1()).public_key(), ec.generate_private_key(ec.SECP256R1()))
    pem_leaf = leaf.public_bytes(serialization.Encoding.PEM)
    (tmp_path / "p" / "attestazione" / "foglia.pem").write_bytes(pem_leaf)
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path / "p", capture_output=True, text=True)
    assert out.returncode == 1 and "Traceback" not in out.stderr
    assert "ERR  attestazione: foglia.pem = primo certificato di catena.pem" in out.stdout
    # catena con una foglia senza estensioni: errore leggibile, niente traceback
    (tmp_path / "p" / "attestazione" / "catena.pem").write_bytes(pem_leaf)
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path / "p", capture_output=True, text=True)
    assert out.returncode == 1 and "Traceback" not in out.stderr and "ERR  attestazione illeggibile" in out.stdout


# --- pagina pubblica e Wi-Fi ---------------------------------------------------------------------------------------


def test_pagina_pubblica_descrive_le_marche_dell_attestazione(env):  # noqa: F811
    client, ph, ca = env
    st, _ = send(client, ph, lambda raw: ca.chain(raw))
    with client.app.state.db.session() as s:
        s.execute(update(Stamp).where(Stamp.target == "attest", Stamp.provider == "accv")
                  .values(status="complete", gen_time=datetime.now(UTC)))
        s.commit()
    sh = share(client, ph, [st["id"]])
    page = client.get("/" + sh["url"].split("://", 1)[1].split("/", 1)[1]).text
    assert "sull&#39;attestazione del telefono" in page or "sull'attestazione del telefono" in page


def test_range_che_salta_l_inizio_conta_la_prima_volta(app, phone):
    pid, _, _ = punch(app, phone)
    sh = share(app, phone, [pid])
    path = "/" + sh["url"].split("://", 1)[1].split("/", 1)[1]
    calls = []
    real = app.app.state.share_limiter.allow
    app.app.state.share_limiter.allow = lambda key: calls.append(key) or real(key)
    for _ in range(3):
        assert app.get(f"{path}/p/{pid}/video", headers={"Range": "bytes=1-"}).status_code == 206
    assert len(calls) == 1  # la prima conta, gli spostamenti successivi no


def test_reti_wifi_di_forma_inattesa():
    assert services.wifi_aps(b'{"wifi": {"aps": {"bssid": "x"}}}') == []
    assert services.wifi_aps(b'{"wifi": {"aps": [{"bssid": "zz:zz"}, {"bssid": "aa:bb:cc:dd:ee:01"}]}}') == [
        {"bssid": "aa:bb:cc:dd:ee:01"}
    ]
    assert services.wifi_aps(b'{"wifi": ["a"]}') == []


def test_marca_riprogrammata_non_riparte_subito(app, phone):
    punch(app, phone)
    db = app.app.state.db
    with db.session() as s:
        st_id = s.scalar(select(Stamp.id).limit(1))
        s.execute(update(Stamp).where(Stamp.id == st_id).values(next_attempt_at=datetime.now(UTC) + timedelta(hours=1)))
        s.commit()
    w = Worker(db, app.app.state.settings)
    w.process_stamp(st_id, due_by=datetime.now(UTC))
    with db.session() as s:
        assert s.get(Stamp, st_id).attempts == 0
    w.stop()

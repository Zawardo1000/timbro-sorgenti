"""Flusso app → backend: associazione, timbratura firmata, video, catena. Senza rete (worker spento)."""

import hashlib
import json
import os

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sqlalchemy import select

from tests.helpers import Phone, body
from timbro import chain
from timbro.models import Device, Punch, Stamp


def test_flusso_completo(app, phone):
    video = os.urandom(50_000)
    raw, sig = phone.punch(video)
    r = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth)
    assert r.status_code == 200, r.text
    st = r.json()
    assert st["sha256"] == hashlib.sha256(video).hexdigest()
    assert not st["video_received"]
    video_stamps = {x["provider"]: x for x in st["stamps"] if x["target"] == "video"}
    record = {x["provider"] for x in st["stamps"] if x["target"] == "record"}
    assert set(video_stamps) == {"accv", "sectigo-q", "ots"}  # solo le qualificate (e Bitcoin)
    assert record == {"accv", "sectigo-q", "ots"}
    assert video_stamps["accv"]["qualified"] and video_stamps["accv"]["status"] == "pending"

    r = app.put(f"/api/v1/punches/{st['id']}/video", content=video, headers=phone.auth)
    assert r.status_code == 200, r.text
    assert r.json()["video_received"]
    settings = app.app.state.settings
    with app.app.state.db.session() as s:
        p = s.get(Punch, st["id"])
        assert (settings.videos_dir / p.video_path).read_bytes() == video
        assert p.manifest_raw == raw  # conservato byte per byte


def test_idempotenza(app, phone):
    video = os.urandom(1000)
    raw, sig = phone.punch(video)
    a = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()
    b = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()
    assert a["id"] == b["id"]
    # stesso client_id con contenuto diverso: conflitto
    cid = json.loads(raw)["client_id"]
    raw2, sig2 = phone.punch(os.urandom(1000), client_id=cid)
    assert app.post("/api/v1/punches", json=body(raw2, sig2), headers=phone.auth).status_code == 409


def test_firma_sbagliata(app, phone):
    raw, sig = phone.punch(b"video")
    other = Phone()
    other.device_id = phone.device_id
    raw2, sig2 = other.punch(b"video")
    assert app.post("/api/v1/punches", json=body(raw, sig2), headers=phone.auth).status_code == 422
    tampered = raw.replace(b'"in"', b'"out"')
    assert app.post("/api/v1/punches", json=body(tampered, sig), headers=phone.auth).status_code == 422


def test_token_mancante_o_errato(app, phone):
    raw, sig = phone.punch(b"video")
    assert app.post("/api/v1/punches", json=body(raw, sig)).status_code == 401
    assert app.post("/api/v1/punches", json=body(raw, sig), headers={"Authorization": "Bearer x"}).status_code == 401


def test_video_non_corrispondente(app, phone):
    video = os.urandom(2000)
    raw, sig = phone.punch(video)
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).json()["id"]
    bad = bytearray(video)
    bad[0] ^= 1
    assert app.put(f"/api/v1/punches/{pid}/video", content=bytes(bad), headers=phone.auth).status_code == 422
    assert app.put(f"/api/v1/punches/{pid}/video", content=video + b"x", headers=phone.auth).status_code == 422
    assert app.put(f"/api/v1/punches/{pid}/video", content=video, headers=phone.auth).status_code == 200
    # nessun file parziale lasciato in giro
    assert not list(app.app.state.settings.videos_dir.rglob("*.part"))


def _links(app):
    from timbro.manifest import public_key_der

    with app.app.state.db.session() as s:
        keys = {d.id: public_key_der(d.public_key_pem) for d in s.scalars(select(Device))}
        return [
            chain.Link(p.prev_chain_hash, p.chain_hash, p.manifest_raw, p.device_signature, keys[p.device_id], p.server_received_at)
            for p in s.scalars(select(Punch).order_by(Punch.id))
        ]


def test_catena(app, phone):
    for _ in range(5):
        raw, sig = phone.punch(os.urandom(100))
        app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth)
    links = _links(app)
    assert chain.verify(links) == 5
    # togliere un anello rompe la catena
    with pytest.raises(ValueError):
        chain.verify(links[:2] + links[3:])


def test_catena_copre_firma_chiave_e_ora(app, phone):
    from dataclasses import replace
    from datetime import timedelta

    raw, sig = phone.punch(os.urandom(100))
    app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth)
    (link,) = _links(app)
    # chi rifirma lo stesso manifest con un'altra chiave, o sposta l'ora di ricezione, rompe la catena
    other = Phone()
    resigned = replace(link, signature=other.key.sign(raw, ec.ECDSA(hashes.SHA256())),
                       device_key_der=other.key.public_key().public_bytes(
                           serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    for bad in (resigned, replace(link, received_at=link.received_at + timedelta(seconds=1))):
        with pytest.raises(ValueError):
            chain.verify([bad])


def test_verifica_archivio_rileva_record_finali_cancellati(app, phone):
    from datetime import timedelta

    from timbro.verify import LOCAL_TZ, verify_archive

    for _ in range(3):
        raw, sig = phone.punch(os.urandom(100))
        app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth)
    db = app.app.state.db
    with db.session() as s:
        last = s.scalar(select(Punch).order_by(Punch.id.desc()))
        day = last.server_received_at.astimezone(LOCAL_TZ).date()
        s.add(Stamp(target="chain", chain_day=day, provider="ots", kind="ots", digest=last.chain_hash))
        s.commit()
        tomorrow = day + timedelta(days=1)
        assert verify_archive(s, today=tomorrow).ok
        # si cancella l'ultimo record (con le sue marche): la catena resta coerente...
        for st in last.stamps:
            s.delete(st)
        s.delete(last)
        s.commit()
        report = verify_archive(s, today=tomorrow)
    # ...ma la marca giornaliera non corrisponde più alla testa
    assert not report.ok and any("testa marcata" in e for e in report.errors)


def test_manifest_con_chiavi_duplicate(app, phone):
    raw, _ = phone.punch(b"v")
    dup = raw[:-1] + b',"kind":"out"}'
    sig = phone.key.sign(dup, ec.ECDSA(hashes.SHA256()))
    r = app.post("/api/v1/punches", json=body(dup, sig), headers=phone.auth)
    assert r.status_code == 422 and "duplicate" in r.text


def test_corpo_troppo_grande_senza_token(app):
    big = b'{"manifest":"' + b"A" * 200_000 + b'"}'
    r = app.post("/api/v1/punches", content=big, headers={"Content-Type": "application/json"})
    assert r.status_code == 413


def test_device_id_diverso(app, phone):
    raw, sig = phone.punch(b"v", device_id="un-altro")
    assert app.post("/api/v1/punches", json=body(raw, sig), headers=phone.auth).status_code == 422

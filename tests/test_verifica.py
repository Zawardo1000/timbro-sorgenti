"""verifica.py del pacchetto di prova (timbro/verifica_pacchetto.py): crittografia in Python puro, marche RFC 3161 con
OCSP/CRL e Trusted List, prove OpenTimestamps, attestazione, e un pacchetto vero verificato da capo a fondo."""

import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.x509.oid import NameOID
from sqlalchemy import select

from tests.test_admin import punch
from timbro import verifica_pacchetto as vp
from timbro.models import Stamp
from timbro.stamping import tsa

F = Path(__file__).parent / "fixtures"
TSA_DIGEST = hashlib.sha256((F / "tsa" / "data.bin").read_bytes()).hexdigest()
REVINFO_DIGEST = (F / "revinfo" / "README.md").read_text(encoding="utf-8").split("Impronta marcata: ")[1].strip()
PROVIDERS = ["accv", "sectigo-q", "freetsa", "digicert"]


def trust(provider):
    return [vp.Cert(c.dump()) for c in tsa.root_certs(provider) + tsa.intermediate_certs(provider)]


# --- crittografia ------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("curve", list(vp.CURVES.values()), ids=lambda c: c[0])
def test_parametri_delle_curve(curve):
    _, p, n, _, G = curve
    assert vp.on_curve(curve, G) and vp.ec_mul(n, G, p) is None


@pytest.mark.parametrize("curve", [ec.SECP256R1(), ec.SECP384R1(), ec.SECP521R1()], ids=lambda c: c.name)
def test_ecdsa_come_cryptography(curve):
    key = ec.generate_private_key(curve)
    spki = vp.parse(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    data = os.urandom(200)
    sig = key.sign(data, ec.ECDSA(hashes.SHA256()))
    assert vp.verify_with_hash(spki, "sha256", data, sig)
    assert not vp.verify_with_hash(spki, "sha256", data + b"x", sig)
    # anche la chiave in forma compressa
    compressed = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.CompressedPoint)
    nums = key.public_key().public_numbers()
    assert vp.decode_point(vp.CURVES[{"secp256r1": "1.2.840.10045.3.1.7", "secp384r1": "1.3.132.0.34",
                                      "secp521r1": "1.3.132.0.35"}[curve.name]], compressed) == (nums.x, nums.y)


def _self_signed(key, **sign_kw):
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "prova")])
    now = datetime.now(UTC)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now).not_valid_after(now + timedelta(days=1))
            .sign(key, hashes.SHA384(), **sign_kw))
    return vp.Cert(cert.public_bytes(serialization.Encoding.DER))


def test_rsa_pkcs1_e_pss():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    assert vp.signs(c := _self_signed(key), c)
    pss = _self_signed(key, rsa_padding=padding.PSS(mgf=padding.MGF1(hashes.SHA384()), salt_length=48))
    assert vp.signs(pss, pss)
    other = _self_signed(rsa.generate_private_key(public_exponent=65537, key_size=2048))
    assert not vp.signs(other, c) and not vp.signs(other, pss)


def test_ripemd160_in_python_puro(monkeypatch):
    real = hashlib.new

    def no_ripemd(name, *a, **kw):
        if name == "ripemd160":
            raise ValueError("non disponibile")
        return real(name, *a, **kw)

    monkeypatch.setattr(vp.hashlib, "new", no_ripemd)
    assert vp.ripemd160(b"").hex() == "9c1185a5c5e9fc54612808977ee8f548b2258d31"
    assert vp.ripemd160(b"abc").hex() == "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"
    assert vp.ripemd160(b"a" * 1000).hex() == "aa69deee9a8922e92f8105e007f76110f381e9cf"


# --- marche RFC 3161 ---------------------------------------------------------------------------------------------


@pytest.mark.parametrize("provider", PROVIDERS)
def test_marche_reali(provider):
    r = vp.verify_timestamp((F / "tsa" / f"{provider}.tsr").read_bytes(), TSA_DIGEST, trust(provider))
    assert r["qtst"] == tsa.PROVIDERS[provider].qualified
    assert r["path"][0].eku() >= {vp.EKU_TIMESTAMPING} and r["gen_time"].year == 2026
    # la radice del percorso è una di quelle fissate sul server
    assert r["path"][-1].sha256 in {sha for _, sha in tsa.PROVIDERS[provider].roots}


@pytest.mark.parametrize("provider", PROVIDERS)
def test_marche_alterate_o_altrui(provider):
    tsr = (F / "tsa" / f"{provider}.tsr").read_bytes()
    with pytest.raises(ValueError, match="impronta"):
        vp.verify_timestamp(tsr, "00" * 32, trust(provider))
    # firma alterata: l'ultimo byte della risposta sta nella firma del SignerInfo
    with pytest.raises(ValueError):
        vp.verify_timestamp(tsr[:-1] + bytes([tsr[-1] ^ 1]), TSA_DIGEST, trust(provider))
    other = "digicert" if provider != "digicert" else "accv"
    with pytest.raises(ValueError):
        vp.verify_timestamp(tsr, TSA_DIGEST, trust(other))


@pytest.mark.parametrize("provider", PROVIDERS)
def test_ocsp_e_crl_conservati(provider):
    r = vp.verify_timestamp((F / "revinfo" / f"{provider}.tsr").read_bytes(), REVINFO_DIGEST, trust(provider))
    files = sorted((F / "revinfo").glob(f"{provider}_ocsp*.der")) + sorted((F / "revinfo").glob(f"{provider}_crl*.der"))
    out = vp.check_revocation(r["path"], r["gen_time"], files)
    assert out and all(level == "OK" for level, _ in out), out
    assert any("non revocato" in msg for _, msg in out)


def test_ocsp_mancante_e_risposta_alterata(tmp_path):
    r = vp.verify_timestamp((F / "revinfo" / "digicert.tsr").read_bytes(), REVINFO_DIGEST, trust("digicert"))
    assert [lv for lv, _ in vp.check_revocation(r["path"], r["gen_time"], [])] == ["ATTN", "ATTN"]
    data = (F / "revinfo" / "digicert_ocsp0.der").read_bytes()
    i = data.index(b"2026")  # producedAt, la prima data della parte firmata
    f = tmp_path / "x_ocsp0.der"
    f.write_bytes(data[:i + 2] + b"2025" + data[i + 6:])
    levels = [lv for lv, _ in vp.check_revocation(r["path"], r["gen_time"], [f])]
    assert "ERR" in levels


def test_trusted_list():
    xml = (F / "tl" / "es_min.xml").read_bytes()
    for provider in ("accv", "sectigo-q"):
        r = vp.verify_timestamp((F / "tsa" / f"{provider}.tsr").read_bytes(), TSA_DIGEST, trust(provider))
        s = vp.tl_status(xml, r["path"], r["gen_time"])
        assert s["granted"] and s["status"] == "granted" and s["sequence"]
        withdrawn = xml.replace(b"Svcstatus/granted", b"Svcstatus/withdrawn")
        assert not vp.tl_status(withdrawn, r["path"], r["gen_time"])["granted"]
    r = vp.verify_timestamp((F / "tsa" / "digicert.tsr").read_bytes(), TSA_DIGEST, trust("digicert"))
    assert vp.tl_status(xml, r["path"], r["gen_time"]) is None  # non qualificata: nessun servizio QTST


# --- OpenTimestamps ----------------------------------------------------------------------------------------------


def test_prova_bitcoin_vera():
    meta = json.loads((F / "ots" / "complete.json").read_text(encoding="utf-8"))
    digest, atts = vp.ots_parse((F / "ots" / "complete.ots").read_bytes())
    assert digest.hex() == meta["digest"]
    [(msg, height)] = [(m, h) for m, kind, h in atts if kind == "bitcoin"]
    assert height == meta["block_height"]
    header = bytes.fromhex(meta["block_header"])
    block_hash, when, difficulty = vp.check_block_header(header, msg, meta["block_hash"])
    assert block_hash == meta["block_hash"] and when.date().isoformat() == "2026-11-05" and difficulty > 1e13
    with pytest.raises(ValueError, match="proof-of-work"):  # nonce cambiato: il lavoro non torna più
        vp.check_block_header(header[:76] + bytes(4), msg)
    with pytest.raises(ValueError, match="Merkle"):
        vp.check_block_header(header, bytes(32))
    with pytest.raises(ValueError, match="blocco indicato"):
        vp.check_block_header(header, msg, "00" * 32)


def test_prova_in_attesa_e_file_rovinati():
    digest, atts = vp.ots_parse((F / "ots" / "pending.ots").read_bytes())
    assert {kind for _, kind, _ in atts} == {"pending"} and digest.hex() == (F / "ots" / "pending.digest").read_text().strip()
    data = (F / "ots" / "complete.ots").read_bytes()
    with pytest.raises(ValueError):
        vp.ots_parse(data[:-5])
    with pytest.raises(ValueError):
        vp.ots_parse(b"x" + data[1:])


# --- attestazione ------------------------------------------------------------------------------------------------


def test_catena_di_attestazione_reale():
    chain = [vp.Cert((F / "attestation" / "ec_tee" / f"cert{i}.der").read_bytes()) for i in range(4)]
    roots = [vp.Cert(d) for p in sorted((Path(vp.__file__).parent / "attestation_roots").glob("*.pem"))
             for d in vp.pem_blocks(p.read_text())]
    problems, root = vp.verify_attestation(chain, roots)
    assert problems == [] and root is not None
    kd = vp.read_key_description(chain[0].ext[vp.KEY_DESCRIPTION][1])
    assert kd["challenge"] == b"abc" and kd["level"] == "TEE" and kd["locked"] is False
    assert vp.verify_attestation(chain[1:], roots)[0]  # senza la foglia: niente KeyDescription
    assert vp.verify_attestation([chain[1], chain[0], *chain[2:]], roots)[0]  # ordine sbagliato: firme che non tornano


# --- un pacchetto vero, da capo a fondo --------------------------------------------------------------------------


def _package(app, phone, tmp_path):
    """Una timbratura il cui video è data.bin: le marche vere dei fixture coprono proprio la sua impronta."""
    settings = app.app.state.settings
    tl_dir = settings.data_dir / "trusted-lists"
    tl_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy(F / "tl" / "es_min.xml", tl_dir / "2026-09-24_ES_189.xml")
    pid, _, _ = punch(app, phone, video=(F / "tsa" / "data.bin").read_bytes())
    with app.app.state.db.session() as s:
        have = set(s.scalars(select(Stamp.provider).where(Stamp.punch_id == pid, Stamp.target == "video")))
        digest = hashlib.sha256((F / "tsa" / "data.bin").read_bytes()).hexdigest()
        for provider in PROVIDERS:  # anche i prestatori non più usati: le loro marche vanno ancora verificate
            if provider not in have:
                s.add(Stamp(target="video", punch_id=pid, provider=provider, kind="rfc3161",
                            qualified=tsa.PROVIDERS[provider].qualified, digest=digest))
        s.flush()
        for st in s.scalars(select(Stamp).where(Stamp.punch_id == pid, Stamp.target == "video", Stamp.kind == "rfc3161")):
            st.token = (F / "tsa" / f"{st.provider}.tsr").read_bytes()
            st.request = (F / "tsa" / f"{st.provider}.tsq").read_bytes()
            st.status, st.gen_time = "complete", datetime(2026, 9, 24, 8, 11, tzinfo=UTC)
        s.commit()
    z = zipfile.ZipFile(io.BytesIO(phone.admin(app, "GET", f"/api/v1/admin/punches/{pid}/evidence.zip").content))
    z.extractall(tmp_path)
    return z


def test_pacchetto_completo_verificato_dallo_script(app, phone, tmp_path):
    """Senza rete: firma del telefono, catena, marche con catene e qualifica, Trusted List del pacchetto."""
    z = _package(app, phone, tmp_path)
    out = subprocess.run([sys.executable, "verifica.py", "--offline"], cwd=tmp_path, capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
    text = out.stdout
    assert "TUTTO VERIFICATO" in text and "ERR" not in text
    assert "OK   manifest.json firmato dalla chiave del telefono" in text
    for provider in PROVIDERS:
        assert f"OK   marca video · {provider}" in text and "firma e catena valide" in text
    assert "OK   marca video · accv (qualificata eIDAS): dichiarata qualificata nel token" in text
    assert "Trusted List n. 189" in text and "stato granted" in text
    assert "VERIFICA.md" in z.namelist() and "python3 verifica.py --offline" in z.read("VERIFICA.md").decode()

    # una marca alterata, una firma del telefono alterata: errori, uscita 1
    tsr = tmp_path / "stamps" / "video_accv.tsr"
    data = tsr.read_bytes()
    tsr.write_bytes(data[:-1] + bytes([data[-1] ^ 1]))
    sig = tmp_path / "manifest.sig"
    # un bit invertito, non un byte a zero: la firma ECDSA cambia a ogni esecuzione e una volta su 256 finisce già con
    # 0x00, e allora "alterarla" così non cambiava niente (CI del 06/11/2026)
    firma = sig.read_bytes()
    sig.write_bytes(firma[:-1] + bytes([firma[-1] ^ 1]))
    out = subprocess.run([sys.executable, "verifica.py", ".", "--offline"], cwd=tmp_path, capture_output=True, text=True)
    assert out.returncode == 1
    assert "ERR  marca video · accv" in out.stdout and "ERR  manifest.json firmato dalla chiave del telefono" in out.stdout


def _official(monkeypatch, extra=None):
    """Fonti ufficiali simulate con i dati dei fixture: nei test niente Internet."""
    from timbro.stamping.tsa import root_certs

    def der(provider):
        return root_certs(provider)[0].dump()

    pages = {
        vp.FONTI["lotl"]: (F / "tl" / "lotl_min.xml").read_bytes(),
        "https://tsl.digital.gob.es/TSL.xml": (F / "tl" / "es_min.xml").read_bytes(),
        vp.FONTI["digicert"]: der("digicert"),
        vp.FONTI["freetsa"]: der("freetsa"),
        **(extra or {}),
    }
    asked = []

    def fake_get(url):
        asked.append(url)
        if url not in pages:
            raise OSError("rete non disponibile nei test: " + url)
        return pages[url]

    monkeypatch.setattr(vp, "_get", fake_get)
    return asked


def test_pacchetto_con_le_fonti_ufficiali(app, phone, tmp_path, monkeypatch, capsys):
    _package(app, phone, tmp_path)
    (tmp_path / "trusted-list").joinpath("2026-09-24_ES_189.xml").unlink()  # vale quella ufficiale, non la copia
    asked = _official(monkeypatch)
    rep = vp.verify_package(tmp_path, online=True)
    text = capsys.readouterr().out
    assert rep.errors == 0, text
    assert "dalle fonti ufficiali in rete" in text
    assert "scaricata ora da https://tsl.digital.gob.es/TSL.xml" in text
    assert "radice DigiCert Trusted Root G4 uguale a quella pubblicata dal prestatore" in text
    assert vp.FONTI["lotl"] in asked and asked.count(vp.FONTI["lotl"]) == 1  # scaricata una volta sola


def test_radice_diversa_da_quella_ufficiale(app, phone, tmp_path, monkeypatch, capsys):
    _package(app, phone, tmp_path)
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    _official(monkeypatch, {vp.FONTI["digicert"]: _self_signed(other).der})
    rep = vp.verify_package(tmp_path, online=True)
    text = capsys.readouterr().out
    assert rep.errors >= 1 and "ERR  marca video · digicert: radice DigiCert Trusted Root G4 uguale" in text


def test_senza_rete_si_usano_le_copie_e_lo_si_dice(app, phone, tmp_path, monkeypatch, capsys):
    _package(app, phone, tmp_path)
    monkeypatch.setattr(vp, "_get", lambda url: (_ for _ in ()).throw(OSError("rete giù")))
    rep = vp.verify_package(tmp_path, online=True)
    text = capsys.readouterr().out
    assert rep.errors == 0, text
    assert "ATTN marca video · accv (qualificata eIDAS): Trusted List ufficiale non scaricata" in text
    assert "copia nel pacchetto" in text


def test_validita_di_ocsp_e_crl_all_ora_della_marca():
    """ACCV dà risposte OCSP emesse mesi prima ma valide fino a dicembre: contano se all'ora della marca non erano
    scadute. Le date si scrivono in UTC anche quando arrivano con un altro fuso."""
    r = vp.verify_timestamp((F / "revinfo" / "accv.tsr").read_bytes(), REVINFO_DIGEST, trust("accv"))
    [(level, msg)] = vp.check_revocation(r["path"], r["gen_time"], [F / "revinfo" / "accv_ocsp0.der"])
    assert level == "OK" and "quindi valida all'ora della marca" in msg and "valida fino al" in msg
    t = datetime(2026, 11, 5, 12, 0, tzinfo=UTC)
    assert vp.validity(t, t + timedelta(days=7), t + timedelta(days=1))[0] == "OK"
    assert vp.validity(t, t + timedelta(days=7), t + timedelta(days=8))[0] == "ATTN"  # scaduta prima della marca
    assert vp.validity(t + timedelta(hours=1), None, t)[0] == "OK"  # emessa dopo la marca
    assert vp.validity(t - timedelta(hours=1), None, t)[0] == "ATTN"
    assert vp.fmt(datetime.fromisoformat("2026-11-05T22:58:36+01:00")) == "05/11/2026 21:58:36 UTC"


def test_pagina_marche_raggruppate_per_prestatore(app, phone, monkeypatch):
    """Sulla pagina: prima le tre marche di ACCV (video, record, attestazione), poi le tre di Sectigo, poi Bitcoin."""
    from timbro import share_view

    pid, _, _ = punch(app, phone)
    with app.app.state.db.session() as s:
        stamps = s.scalars(select(Stamp).where(Stamp.punch_id == pid)).all()
        for st in stamps:
            st.status, st.gen_time = "complete", datetime.now(UTC)
        # anche un'attestazione: tre oggetti per prestatore
        for provider, kind in (("accv", "rfc3161"), ("sectigo-q", "rfc3161"), ("ots", "ots")):
            s.add(Stamp(target="attest", punch_id=pid, provider=provider, kind=kind, qualified=kind == "rfc3161",
                        digest="00" * 32, status="complete", gen_time=datetime.now(UTC)))
        s.commit()
        order = [(st.provider, st.target) for st in sorted(s.get(share_view.Punch, pid).stamps, key=share_view._stamp_order)]
    assert order == [(p, t) for p in ("accv", "sectigo-q", "ots") for t in ("video", "record", "attest")]
    r = phone.admin(app, "POST", "/api/v1/admin/shares", {"punch_ids": [pid], "label": "x"})
    page = app.get("/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]).text
    first = [page.index(name) for name in ("ACCV · Generalitat", "Sectigo Qualified Time Stamping<", "OpenTimestamps · Bitcoin")]
    assert first == sorted(first) and page.count('<li class="group">') == 3

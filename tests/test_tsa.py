"""Verifica delle marche RFC 3161 su risposte reali registrate in M0 (tests/fixtures/tsa)."""

import hashlib
from pathlib import Path

import pytest
from asn1crypto import tsp

from timbro.stamping import tsa

F = Path(__file__).parent / "fixtures" / "tsa"
DIGEST = hashlib.sha256((F / "data.bin").read_bytes()).digest()


def recorded(name: str) -> tuple[bytes, int]:
    nonce = tsp.TimeStampReq.load((F / f"{name}.tsq").read_bytes())["nonce"].native
    return (F / f"{name}.tsr").read_bytes(), nonce


@pytest.mark.parametrize("name", list(tsa.PROVIDERS))
def test_risposte_reali_valide(name):
    tsr, nonce = recorded(name)
    info = tsa.verify_response(name, tsr, DIGEST, nonce)
    assert info.gen_time.year == 2026
    assert info.signer


def test_accv_precisione_e_policy():
    tsr, nonce = recorded("accv")
    info = tsa.verify_response("accv", tsr, DIGEST, nonce)
    assert info.accuracy_ms == 500
    assert info.policy == "1.3.6.1.4.1.8149.3.100.2.0"


def test_impronta_sbagliata():
    tsr, nonce = recorded("accv")
    with pytest.raises(tsa.StampError, match="impronta"):
        tsa.verify_response("accv", tsr, hashlib.sha256(b"altro").digest(), nonce)


def test_nonce_sbagliato():
    tsr, nonce = recorded("accv")
    with pytest.raises(tsa.StampError, match="nonce"):
        tsa.verify_response("accv", tsr, DIGEST, nonce + 1)


def test_root_sbagliata(monkeypatch):
    # un token ACCV verificato contro la root DigiCert non deve risultare fidato
    tsr, nonce = recorded("accv")
    digicert_roots = tsa.root_certs("digicert")
    monkeypatch.setattr(tsa, "root_certs", lambda _p: digicert_roots)
    with pytest.raises(tsa.StampError, match="catena|firma"):
        tsa.verify_response("accv", tsr, DIGEST, nonce)


def test_risposta_spazzatura():
    with pytest.raises(tsa.StampError):
        tsa.verify_response("accv", b"non sono una marca", DIGEST, 1)


def test_richiesta_solo_sha256():
    with pytest.raises(ValueError):
        tsa.build_request(b"corto")
    der, nonce = tsa.build_request(DIGEST)
    req = tsp.TimeStampReq.load(der)
    assert req["message_imprint"]["hash_algorithm"]["algorithm"].native == "sha256"
    assert req["nonce"].native == nonce and req["cert_req"].native is True


@pytest.mark.parametrize("name,expected", [("accv", True), ("sectigo-q", True), ("freetsa", False), ("digicert", False)])
def test_qualifica_verificata(name, expected):
    tsr, nonce = recorded(name)
    assert tsa.verify_response(name, tsr, DIGEST, nonce).qualified is expected


def test_qualificata_senza_servizio_tl(monkeypatch):
    # se il certificato del servizio TL non è nel percorso, la marca non è accettata come qualificata
    from dataclasses import replace

    tsr, nonce = recorded("accv")
    monkeypatch.setitem(tsa.PROVIDERS, "accv", replace(tsa.PROVIDERS["accv"], tl_service_sha256=("00" * 32,)))
    with pytest.raises(tsa.StampError, match="Trusted List"):
        tsa.verify_response("accv", tsr, DIGEST, nonce)


def test_qualificata_senza_qtst_statement(monkeypatch):
    # un token senza esi4-qtstStatement-1 (FreeTSA) non può passare per qualificato
    from dataclasses import replace

    tsr, nonce = recorded("freetsa")
    monkeypatch.setitem(tsa.PROVIDERS, "freetsa", replace(tsa.PROVIDERS["freetsa"], qualified=True, tl_service_sha256=("00" * 32,)))
    with pytest.raises(tsa.StampError, match="qtstStatement"):
        tsa.verify_response("freetsa", tsr, DIGEST, nonce)


# --- EU Trusted List (estratti reali della LOTL e della TL spagnola n. 189 del 24/09/2026) ---

TL = Path(__file__).parent / "fixtures" / "tl"


def test_lotl_indirizzo_tl_spagnola():
    from timbro.stamping.trusted_list import national_tl_url

    assert national_tl_url((TL / "lotl_min.xml").read_bytes(), "ES") == "https://tsl.digital.gob.es/TSL.xml"


@pytest.mark.parametrize("name", ["accv", "sectigo-q"])
def test_servizio_qtst_granted(name):
    from timbro.stamping.trusted_list import check_provider

    results = check_provider((TL / "es_min.xml").read_bytes(), name)
    assert len(results) == len(tsa.PROVIDERS[name].tl_service_sha256)
    assert all(r.found and r.granted and r.status == "granted" for r in results)


def test_servizio_revocato():
    from timbro.stamping.trusted_list import check_provider

    xml = (TL / "es_min.xml").read_bytes().replace(b"Svcstatus/granted", b"Svcstatus/withdrawn")
    results = check_provider(xml, "accv")
    assert all(r.found and not r.granted and r.status == "withdrawn" for r in results)


def test_rifiuto_della_tsa():
    # risposta reale di ACCV a una richiesta con la policy della nuova gerarchia (non ancora attiva)
    tsr = (F / "accv_rejected_policy.tsr").read_bytes()
    with pytest.raises(tsa.StampError, match="rejection.*unaccepted_policy"):
        tsa.verify_response("accv", tsr, DIGEST, 1)


def test_identita_tl_duplicata_vale_la_granted():
    # stessa identità in due servizi: uno ritirato (dopo nel documento) e uno granted
    import re

    from timbro.stamping.trusted_list import check_provider

    xml = (TL / "es_min.xml").read_text(encoding="utf-8")
    svc = re.search(r"<tsl:TSPService>(?:(?!</tsl:TSPService>).)*?TSA1 ACCV 2016.*?</tsl:TSPService>", xml, re.S).group(0)
    withdrawn = svc.replace("Svcstatus/granted", "Svcstatus/withdrawn").replace("2019-12-23", "2030-01-01")
    xml = xml.replace(svc, svc + withdrawn)
    r = next(r for r in check_provider(xml.encode(), "accv") if r.identity.startswith("2c8d4233"))
    assert r.granted


def test_rifiuto_permanente():
    tsr = (F / "accv_rejected_policy.tsr").read_bytes()
    with pytest.raises(tsa.StampError) as e:
        tsa.verify_response("accv", tsr, DIGEST, 1)
    assert e.value.permanent


@pytest.mark.parametrize("fail, text, permanent", [
    ({"add_info_not_available"}, "Error during serial number generation.", False),  # FreeTSA, 05/11/2026
    (set(), "", False),
    ({"system_failure"}, "", False),
    ({"bad_alg"}, "", True),
    ({"bad_request"}, "", True),
    ({"unaccepted_policy", "system_failure"}, "", False),
])
def test_rifiuti_temporanei_e_definitivi(fail, text, permanent):
    info = {"status": "rejection"}
    if fail:
        info["fail_info"] = tsp.PKIFailureInfo(fail)
    if text:
        info["status_string"] = [text]
    resp = tsa._TimeStampResp({"status": tsp.PKIStatusInfo(info)}).dump()
    with pytest.raises(tsa.StampError) as e:
        tsa.verify_response("freetsa", resp, DIGEST, 1)
    assert e.value.permanent is permanent


def test_granted_senza_token():
    # stato "granted" ma token assente: errore controllato, non un'eccezione di asn1crypto
    status = tsp.PKIStatusInfo({"status": "granted"})
    resp = tsa._TimeStampResp({"status": status}).dump()
    with pytest.raises(tsa.StampError, match="token"):
        tsa.verify_response("accv", resp, DIGEST, 1)


def test_identita_tl_registrata_nella_marca():
    tsr, nonce = recorded("accv")
    assert tsa.verify_response("accv", tsr, DIGEST, nonce).tl_identity.startswith("2c8d4233")


@pytest.mark.parametrize("ca", ["accv-2024-rsa1-ca", "accv-2024-ecc1-ca"])
def test_intermedie_accv_2024_validabili(ca):
    # le CA dei nuovi servizi ACCV si validano con pyHanko fino alle root 2024 fissate
    # (compresa la root ECC P-521/SHA-512), senza scaricare nulla
    import asyncio

    from pyhanko_certvalidator import CertificateValidator, ValidationContext

    inter = {stem: tsa.load_root(stem, sha) for stem, sha in tsa.PROVIDERS["accv"].intermediates}[ca]
    ctx = ValidationContext(trust_roots=tsa.root_certs("accv"), allow_fetching=False, revocation_mode="soft-fail")
    path = asyncio.run(CertificateValidator(inter, validation_context=ctx).async_validate_path())
    assert "2024" in path.first.subject.native["common_name"]


def test_provider_qualificato_senza_identita_tl():
    with pytest.raises(ValueError, match="identità TL"):
        tsa.Provider("x", "http://x", True, (("accv", "00"),))


def test_marca_nuova_con_gentime_lontano_dall_orologio():
    # una marca "appena ottenuta" con genTime a ore di distanza dall'ora del server è rifiutata
    from datetime import timedelta

    tsr, nonce = recorded("accv")
    gen = tsa.verify_response("accv", tsr, DIGEST, nonce).gen_time
    with pytest.raises(tsa.StampError, match="dista"):
        tsa.verify_response("accv", tsr, DIGEST, nonce, now=gen + timedelta(hours=3))

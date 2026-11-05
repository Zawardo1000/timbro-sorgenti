"""Manifest della timbratura: JSON firmato dal telefono (ECDSA P-256 / SHA-256, chiave nel Keystore).

Il server non ricanonicalizza nulla: verifica la firma sui byte esatti ricevuti e li conserva così.
"""

import json
from typing import Literal

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from timbro.validation import NoNulStr


class Location(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    accuracy_m: float | None = None
    fix_time: str | None = None
    provider: str | None = None
    is_mock: bool | None = None


class WifiAp(BaseModel):
    """Un access point visto dal telefono: solo l'indirizzo (BSSID), mai il nome della rete."""

    bssid: str = Field(pattern=r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    rssi: int | None = Field(default=None, ge=-127, le=0)
    mhz: int | None = Field(default=None, ge=2000, le=8000)


class Wifi(BaseModel):
    scan_time: NoNulStr | None = Field(default=None, max_length=40)
    aps: list[WifiAp] = Field(default_factory=list, max_length=64)


class Clocks(BaseModel):
    """Orologi che chi usa il telefono non può spostare dalle impostazioni, letti dall'app all'inizio della ripresa:
    confermano (o smentiscono) device_time, che invece si cambia a mano. Ora di rete (NTP/rete cellulare, Android 13+)
    e ora satellitare (GNSS), riportate all'istante d'inizio con l'orologio monotono del telefono; auto_time dice se in
    quel momento l'ora del telefono era impostata in automatico."""

    auto_time: bool | None = None
    network: NoNulStr | None = Field(default=None, max_length=40)
    gnss: NoNulStr | None = Field(default=None, max_length=40)


class Manifest(BaseModel):
    # campi sconosciuti ammessi: restano comunque nei byte firmati e conservati
    model_config = ConfigDict(extra="ignore")

    v: Literal[1]
    client_id: str = Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    device_id: NoNulStr
    kind: Literal["in", "out"]
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    # interi a 32 bit come le colonne: un valore più grande farebbe fallire l'inserimento, e il telefono
    # ritenterebbe per sempre una timbratura che non può entrare
    size: int = Field(gt=0, le=2**31 - 1)
    duration_ms: int = Field(ge=0, le=2**31 - 1)
    device_time: NoNulStr = Field(max_length=40)
    location: Location | None = None
    # reti Wi-Fi visibili alla timbratura: le stesse della sede, giorno dopo giorno, e verificabili da un perito sul
    # posto (più difficili da falsificare del GPS: serve il root, che l'attestazione rivela)
    wifi: Wifi | None = None
    clocks: Clocks | None = None
    app_version: NoNulStr = Field(max_length=40)


class ManifestError(ValueError):
    pass


def _no_duplicate_keys(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise ValueError("chiavi duplicate nel JSON")
    return dict(pairs)


def _no_constants(name):
    raise ValueError(f"valore non ammesso: {name}")


def strict_json(raw: bytes):
    """JSON senza ambiguità: niente chiavi duplicate (parser diversi le leggerebbero diversamente),
    niente NaN/Infinity."""
    return json.loads(raw, object_pairs_hook=_no_duplicate_keys, parse_constant=_no_constants)


def canonical_pem(pem: str) -> str:
    """La stessa chiave in forma canonica (SubjectPublicKeyInfo, punto non compresso)."""
    return load_public_key(pem).public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()


def public_key_der(pem: str) -> bytes:
    return load_public_key(pem).public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


def load_public_key(pem: str) -> ec.EllipticCurvePublicKey:
    try:
        key = serialization.load_pem_public_key(pem.encode())
    except ValueError as e:
        raise ManifestError(f"chiave pubblica non valida: {e}") from e
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ManifestError("serve una chiave EC P-256")
    return key


def verify_and_parse(public_key_pem: str, raw: bytes, signature: bytes) -> Manifest:
    try:
        load_public_key(public_key_pem).verify(signature, raw, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as e:
        raise ManifestError("firma del manifest non valida") from e
    try:
        return Manifest.model_validate(strict_json(raw))
    except (ValueError, ValidationError) as e:
        raise ManifestError(f"manifest non valido: {e}") from e

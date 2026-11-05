"""Catena dei record: ogni timbratura incorpora l'hash della precedente.

chain_hash = sha256(prev || sha256(manifest_raw) || sha256(firma) || sha256(chiave pubblica DER)
                    || ora di ricezione ISO 8601 UTC)
con prev iniziale = 32 byte a zero. Rimuovere, riordinare o alterare un record (anche sostituendo
chiave e firme, o l'ora di ricezione) rompe tutti gli anelli successivi. Ogni timbratura ha
marche proprie sul chain_hash e la testa della catena viene marcata ogni giorno.
"""

import hashlib
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime

GENESIS = "00" * 32


@dataclass(frozen=True)
class Link:
    prev: str
    chain: str
    manifest_raw: bytes
    signature: bytes
    device_key_der: bytes
    received_at: datetime


def link(
    prev_chain_hash: str, manifest_raw: bytes, signature: bytes, device_key_der: bytes, received_at: datetime
) -> str:
    if received_at.tzinfo is None:
        raise ValueError("ora di ricezione senza fuso")
    h = hashlib.sha256()
    h.update(bytes.fromhex(prev_chain_hash))
    h.update(hashlib.sha256(manifest_raw).digest())
    h.update(hashlib.sha256(signature).digest())
    h.update(hashlib.sha256(device_key_der).digest())
    h.update(received_at.isoformat().encode())
    return h.hexdigest()


def verify(records: Iterable[Link]) -> int:
    """Verifica gli anelli in ordine; ritorna il numero di anelli, o alza ValueError."""
    expected_prev = GENESIS
    n = 0
    for r in records:
        if r.prev != expected_prev:
            raise ValueError(f"anello {n}: prev {r.prev} diverso dall'atteso {expected_prev}")
        if link(r.prev, r.manifest_raw, r.signature, r.device_key_der, r.received_at) != r.chain:
            raise ValueError(f"anello {n}: hash della catena non corrisponde")
        expected_prev = r.chain
        n += 1
    return n

"""OpenTimestamps: invio ai calendar, upgrade delle prove e verifica dell'ancoraggio Bitcoin.

Si usa la libreria di base `opentimestamps` e non la CLI: su Windows `opentimestamps-client`
importa python-bitcoinlib, che cerca libssl via ctypes e fallisce. La verifica dell'attestazione
Bitcoin confronta la radice di Merkle con l'header del blocco preso da un explorer pubblico.
"""

import hashlib
import os
from dataclasses import dataclass

import httpx
from opentimestamps.calendar import CommitmentNotFoundError, RemoteCalendar, UrlWhitelist
from opentimestamps.core.notary import BitcoinBlockHeaderAttestation, PendingAttestation
from opentimestamps.core.op import OpAppend, OpSHA256
from opentimestamps.core.serialize import BytesDeserializationContext, BytesSerializationContext
from opentimestamps.core.timestamp import DetachedTimestampFile, Timestamp

USER_AGENT = "timbro"


class OtsError(Exception):
    pass


@dataclass(frozen=True)
class UpgradeResult:
    ots: bytes
    complete: bool
    block_height: int | None = None
    block_time: int | None = None
    block_hash: str | None = None
    # intestazione del blocco (80 byte, esadecimale): nel pacchetto di prova permette di verificare radice di Merkle e
    # proof-of-work senza rete (verifica.py)
    block_header: str | None = None
    errors: tuple[str, ...] = ()


def _dump(f: DetachedTimestampFile) -> bytes:
    ctx = BytesSerializationContext()
    f.serialize(ctx)
    return ctx.getbytes()


def _load(data: bytes) -> DetachedTimestampFile:
    return DetachedTimestampFile.deserialize(BytesDeserializationContext(data))


def stamp(digest: bytes, calendars: list[str], timeout: float = 15) -> tuple[bytes, list[str]]:
    """Crea una prova .ots (pending) per un'impronta SHA-256. Ritorna i calendar che hanno accettato.

    Come il client ufficiale, prima dell'invio si aggiunge un nonce casuale:
    i calendar non vedono l'impronta vera.
    """
    f = DetachedTimestampFile(OpSHA256(), Timestamp(digest))
    merkle = f.timestamp.ops.add(OpAppend(os.urandom(16))).ops.add(OpSHA256())
    accepted = []
    errors = []
    for url in calendars:
        try:
            merkle.merge(RemoteCalendar(url, user_agent=USER_AGENT).submit(merkle.msg, timeout=timeout))
            accepted.append(url)
        except Exception as e:
            errors.append(f"{url}: {e!r}")
    if not accepted:
        raise OtsError("nessun calendar ha accettato: " + "; ".join(errors))
    return _dump(f), accepted


def _block(height: int, explorer: str, client: httpx.Client) -> tuple[str, bytes, int, bytes]:
    """(hash, radice di Merkle come nell'intestazione, ora, intestazione) del blocco a quell'altezza. Radice e ora si
    leggono dall'intestazione, dopo aver controllato che il suo doppio SHA-256 sia proprio l'hash del blocco."""
    block_hash = client.get(f"{explorer}/block-height/{height}", timeout=20).raise_for_status().text.strip()
    header = bytes.fromhex(client.get(f"{explorer}/block/{block_hash}/header", timeout=20).raise_for_status().text.strip())
    if len(header) != 80 or hashlib.sha256(hashlib.sha256(header).digest()).digest()[::-1].hex() != block_hash:
        raise OtsError(f"intestazione del blocco {height} non corrispondente al suo hash")
    return block_hash, header[36:68], int.from_bytes(header[68:72], "little"), header


def upgrade(
    data: bytes, whitelist: list[str], explorer: str, client: httpx.Client, timeout: float = 15
) -> UpgradeResult:
    """Chiede ai calendar le parti mancanti della prova e verifica gli eventuali ancoraggi Bitcoin.

    Un'attestazione che non torna con la blockchain viene segnalata ma non blocca le altre;
    la prova è completa se almeno un'attestazione Bitcoin è verificata.
    """
    f = _load(data)
    allowed = UrlWhitelist(whitelist)
    errors: list[str] = []
    for msg, att in list(f.timestamp.all_attestations()):
        if not isinstance(att, PendingAttestation) or att.uri not in allowed:
            continue
        try:
            sub = RemoteCalendar(att.uri, user_agent=USER_AGENT).get_timestamp(msg, timeout=timeout)
        except CommitmentNotFoundError:
            continue  # non ancora ancorata: normale nelle prime ore
        except Exception as e:
            errors.append(f"{att.uri}: {type(e).__name__}: {e}")
            continue
        _find(f.timestamp, msg).merge(sub)

    best: tuple[int, int, str, str] | None = None
    for msg, att in f.timestamp.all_attestations():
        if not isinstance(att, BitcoinBlockHeaderAttestation):
            continue
        try:
            block_hash, root, block_time, header = _block(att.height, explorer, client)
        except Exception as e:
            errors.append(f"blocco {att.height}: {type(e).__name__}: {e}")
            continue
        if root != msg:
            errors.append(f"blocco {att.height}: la radice di Merkle non corrisponde")
            continue
        if best is None or att.height < best[0]:
            best = (att.height, block_time, block_hash, header.hex())
    if best:
        return UpgradeResult(_dump(f), True, best[0], best[1], best[2], best[3], tuple(errors))
    return UpgradeResult(_dump(f), False, errors=tuple(errors))


def _find(ts: Timestamp, msg: bytes) -> Timestamp:
    if ts.msg == msg:
        return ts
    for sub in ts.ops.values():
        found = _find(sub, msg)
        if found is not None:
            return found
    return None


def info(data: bytes) -> str:
    return _load(data).timestamp.str_tree()

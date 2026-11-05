"""M0 — sonda OpenTimestamps: invia l hash di scratch/probe/data.bin ai calendar e salva il .ots (pending).

Usa la libreria core `opentimestamps`, non la CLI: su Windows `opentimestamps-client` importa
python-bitcoinlib, che cerca libssl via ctypes e fallisce.
"""
import hashlib
from opentimestamps.core.op import OpSHA256, OpAppend
from opentimestamps.core.timestamp import Timestamp, DetachedTimestampFile
from opentimestamps.core.serialize import StreamSerializationContext
from opentimestamps.calendar import RemoteCalendar
import os
data = open("scratch/probe/data.bin","rb").read()
f = DetachedTimestampFile(OpSHA256(), Timestamp(hashlib.sha256(data).digest()))
# nonce come fa il client ufficiale, per non rivelare l'hash ai calendar
nonce_stamp = f.timestamp.ops.add(OpAppend(os.urandom(16)))
merkle = nonce_stamp.ops.add(OpSHA256())
ok = 0
for url in ["https://a.pool.opentimestamps.org", "https://b.pool.opentimestamps.org", "https://a.pool.eternitywall.com", "https://ots.btc.catallaxy.com"]:
    try:
        merkle.merge(RemoteCalendar(url).submit(merkle.msg, timeout=15)); ok += 1; print("ok", url)
    except Exception as e:
        print("ERR", url, repr(e))
with open("scratch/probe/data.bin.ots","wb") as fd:
    f.serialize(StreamSerializationContext(fd))
print("calendar ok:", ok)
print(f.timestamp.str_tree())

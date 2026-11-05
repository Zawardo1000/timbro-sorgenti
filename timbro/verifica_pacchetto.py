#!/usr/bin/env python3
"""Verifica completa di un pacchetto di prova Timbro, solo con la libreria standard di Python (3.8 o successivo).

    python3 verifica.py              radici di fiducia prese dalle fonti ufficiali in rete
    python3 verifica.py --offline    senza rete: radici di fiducia prese dal pacchetto (impronte da confrontare)

Fonti ufficiali usate in rete (indirizzi qui sotto, in FONTI): radici di Google per l'attestazione e lista di revoca
di Google; Trusted List europea (ec.europa.eu) e lista nazionale indicata da quella, per i prestatori qualificati;
siti dei prestatori non qualificati per le loro radici; due explorer Bitcoin indipendenti per i blocchi.

Controlla, senza fidarsi del server che ha prodotto il pacchetto:
  1. impronte: video, manifest, firma e chiave del telefono, anelli della catena fino alla testa marcata del giorno;
  2. la firma del telefono su manifest.json, con device_pubkey.pem;
  3. l'attestazione hardware: challenge = SHA-256 del manifest, catena firmata fino a una radice di Google, cosa
     attesta (hardware sicuro, bootloader, avvio verificato, app, versione e certificato di firma dell'app);
  4. le marche RFC 3161: impronta marcata, firma, certificato di marcatura e catena fino alla radice del prestatore,
     validità all'ora della marca, qualifica eIDAS dichiarata nel token, risposte OCSP e CRL conservate, servizio
     qualificato nella Trusted List inclusa;
  5. le prove OpenTimestamps: percorso dall'impronta alla radice di Merkle di un blocco Bitcoin e intestazione del
     blocco con il suo proof-of-work;
  6. la coerenza delle ore: ora del telefono, orologi di rete e satellitare scritti nel manifest firmato, ricezione
     del server e marche temporali (un orologio spostato o una catena riscritta lasciano ore che non tornano);
  7. la provenienza del codice: in rete, che questo script sia identico a quello pubblicato per la versione del
     server che ha prodotto il pacchetto, e che le release pubbliche di app e server esistano.

In rete le radici e lo stato dei servizi qualificati vengono dalle fonti ufficiali, scaricate in HTTPS; senza rete
dal pacchetto, e lo script ne stampa le impronte SHA-256 da confrontare. La firma XML della Trusted List non viene
verificata (la si scarica dal dominio ufficiale indicato dalla Commissione): si può controllare anche sul sito della
Commissione europea (https://eidas.ec.europa.eu/efda/tl-browser/).
Crittografia (RSA, ECDSA sulle curve NIST P-256, P-384 e P-521, RIPEMD-160) scritta qui in Python puro: lenta
rispetto a openssl, ma bastano pochi secondi e non serve installare niente.
"""

import base64
import hashlib
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

UTC = timezone.utc


# --- DER (ASN.1) -------------------------------------------------------------------------------------------------


class Node:
    """Un elemento DER: classe (0 universale, 2 contesto), costruito sì/no, numero del tag, valore e byte completi."""

    __slots__ = ("cls", "cons", "num", "val", "raw")

    def __init__(self, cls, cons, num, val, raw):
        self.cls, self.cons, self.num, self.val, self.raw = cls, cons, num, val, raw

    def kids(self):
        return parse_all(self.val)

    def is_(self, num, cls=0):
        return self.cls == cls and self.num == num


def parse_one(data, pos=0):
    start = pos
    first = data[pos]
    pos += 1
    cls, cons, num = first >> 6, bool(first & 0x20), first & 0x1F
    if num == 0x1F:
        num = 0
        while True:
            b = data[pos]
            pos += 1
            num = (num << 7) | (b & 0x7F)
            if not b & 0x80:
                break
    n = data[pos]
    pos += 1
    if n & 0x80:
        k = n & 0x7F
        if k == 0 or k > 4:
            raise ValueError("lunghezza DER non valida")
        n = int.from_bytes(data[pos:pos + k], "big")
        pos += k
    end = pos + n
    if end > len(data):
        raise ValueError("DER troncato")
    return Node(cls, cons, num, data[pos:end], data[start:end]), end


def parse(data):
    node, end = parse_one(data)
    if end != len(data):
        raise ValueError("byte in eccesso dopo il DER")
    return node


def parse_all(data):
    out, pos = [], 0
    while pos < len(data):
        node, pos = parse_one(data, pos)
        out.append(node)
    return out


def oid(node):
    subs, n = [], 0
    for b in node.val:
        n = (n << 7) | (b & 0x7F)
        if not b & 0x80:
            subs.append(n)
            n = 0
    a = 0 if subs[0] < 40 else 1 if subs[0] < 80 else 2
    return ".".join(str(x) for x in [a, subs[0] - 40 * a] + subs[1:])


def integer(node):
    return int.from_bytes(node.val, "big", signed=True)


def bits(node):
    """Contenuto di un BIT STRING senza il byte dei bit inutilizzati."""
    return node.val[1:]


def parse_time(node):
    s = node.val.decode("ascii")
    if node.num == 23:  # UTCTime
        s = ("19" if int(s[:2]) >= 50 else "20") + s
    m = re.match(r"^(\d{14})(?:[.,](\d+))?Z$", s)
    if not m:
        raise ValueError("ora non valida: " + s)
    dt = datetime.strptime(m.group(1), "%Y%m%d%H%M%S").replace(tzinfo=UTC)
    if m.group(2):
        dt = dt.replace(microsecond=int((m.group(2) + "000000")[:6]))
    return dt


def pem_blocks(text):
    return [base64.b64decode("".join(b.split())) for b in re.findall(r"-----BEGIN [^-]+-----(.*?)-----END [^-]+-----", text, re.S)]


def fmt(dt):
    return dt.astimezone(UTC).strftime("%d/%m/%Y %H:%M:%S UTC")


def validity(this_update, next_update, when):
    """Una risposta OCSP o una CRL dice "non revocato" per l'ora della marca se è stata emessa dopo (una revoca non si
    annulla) oppure se all'ora della marca non era ancora scaduta (nextUpdate). (livello, testo del periodo)."""
    period = "emessa il %s" % fmt(this_update) + (", valida fino al %s" % fmt(next_update) if next_update else "")
    if this_update >= when:
        return "OK", period + ", dopo la marca"
    if next_update is None:
        return "ATTN", period + ", senza scadenza e precedente alla marca: non dice com'era all'ora della marca"
    if next_update >= when:
        return "OK", period + ", quindi valida all'ora della marca"
    return "ATTN", period + ": già scaduta all'ora della marca"


NAMES = {"2.5.4.3": "CN", "2.5.4.10": "O", "2.5.4.11": "OU", "2.5.4.6": "C", "2.5.4.5": "serialNumber"}


def name_fields(node):
    out = {}
    for rdn in node.kids():
        for atv in rdn.kids():
            t, v = atv.kids()[:2]
            key = NAMES.get(oid(t))
            if key and key not in out:
                try:
                    out[key] = v.val.decode("utf-16-be") if v.num == 30 else v.val.decode("utf-8")
                except UnicodeDecodeError:
                    out[key] = v.val.decode("latin-1")
    return out


# --- crittografia ------------------------------------------------------------------------------------------------

HASHES = {
    "1.3.14.3.2.26": "sha1", "2.16.840.1.101.3.4.2.4": "sha224", "2.16.840.1.101.3.4.2.1": "sha256",
    "2.16.840.1.101.3.4.2.2": "sha384", "2.16.840.1.101.3.4.2.3": "sha512",
}
SIG_ALGS = {
    "1.2.840.113549.1.1.5": ("rsa", "sha1"), "1.2.840.113549.1.1.14": ("rsa", "sha224"),
    "1.2.840.113549.1.1.11": ("rsa", "sha256"), "1.2.840.113549.1.1.12": ("rsa", "sha384"),
    "1.2.840.113549.1.1.13": ("rsa", "sha512"), "1.2.840.10045.4.1": ("ec", "sha1"),
    "1.2.840.10045.4.3.1": ("ec", "sha224"), "1.2.840.10045.4.3.2": ("ec", "sha256"),
    "1.2.840.10045.4.3.3": ("ec", "sha384"), "1.2.840.10045.4.3.4": ("ec", "sha512"),
}
RSA_KEY, RSA_PSS, MGF1, EC_KEY = "1.2.840.113549.1.1.1", "1.2.840.113549.1.1.10", "1.2.840.113549.1.1.8", "1.2.840.10045.2.1"

# curve NIST (a = -3): nome, p, n, b, generatore
CURVES = {
    "1.2.840.10045.3.1.7": (
        "P-256",
        0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF,
        0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551,
        0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B,
        (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
         0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5),
    ),
    "1.3.132.0.34": (
        "P-384",
        0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF,
        0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973,
        0xB3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF,
        (0xAA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7,
         0x3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F),
    ),
    "1.3.132.0.35": (
        "P-521",
        (1 << 521) - 1,
        0x01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409,
        0x0051953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918EF109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46B503F00,
        (0x00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C2E5BD66,
         0x011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD17273E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE94769FD16650),
    ),
}


def ec_add(P, Q, p):
    if P is None:
        return Q
    if Q is None:
        return P
    (x1, y1), (x2, y2) = P, Q
    if x1 == x2:
        if (y1 + y2) % p == 0:
            return None
        lam = (3 * x1 * x1 - 3) * pow(2 * y1, -1, p) % p
    else:
        lam = (y2 - y1) * pow(x2 - x1, -1, p) % p
    x3 = (lam * lam - x1 - x2) % p
    return x3, (lam * (x1 - x3) - y1) % p


def ec_mul(k, P, p):
    R = None
    while k:
        if k & 1:
            R = ec_add(R, P, p)
        P = ec_add(P, P, p)
        k >>= 1
    return R


def on_curve(curve, P):
    _, p, _, b, _ = curve
    x, y = P
    return 0 <= x < p and 0 <= y < p and (y * y - (x ** 3 - 3 * x + b)) % p == 0


def decode_point(curve, raw):
    _, p, _, b, _ = curve
    size = (p.bit_length() + 7) // 8
    if raw[:1] == b"\x04" and len(raw) == 1 + 2 * size:
        P = int.from_bytes(raw[1:1 + size], "big"), int.from_bytes(raw[1 + size:], "big")
    elif raw[:1] in (b"\x02", b"\x03") and len(raw) == 1 + size:
        x = int.from_bytes(raw[1:], "big")
        y = pow((x ** 3 - 3 * x + b) % p, (p + 1) // 4, p)  # p ≡ 3 mod 4 per tutte e tre le curve
        if y % 2 != raw[0] - 2:
            y = p - y
        P = x, y
    else:
        raise ValueError("punto della curva in un formato non valido")
    if not on_curve(curve, P):
        raise ValueError("punto fuori dalla curva")
    return P


def ecdsa_verify(curve, Q, digest, sig_der):
    _, p, n, _, G = curve
    try:
        r, s = (integer(x) for x in parse(sig_der).kids()[:2])
    except (ValueError, IndexError):
        return False
    if not (1 <= r < n and 1 <= s < n):
        return False
    e = int.from_bytes(digest, "big")
    if len(digest) * 8 > n.bit_length():
        e >>= len(digest) * 8 - n.bit_length()
    w = pow(s, -1, n)
    X = ec_add(ec_mul(e * w % n, G, p), ec_mul(r * w % n, Q, p), p)
    return X is not None and X[0] % n == r


def rsa_pkcs1_verify(n, e, h, data, signature):
    k = (n.bit_length() + 7) // 8
    s = int.from_bytes(signature, "big")
    if len(signature) > k or s >= n:
        return False
    em = pow(s, e, n).to_bytes(k, "big")
    try:
        i = em.index(b"\x00", 2)
        if em[:2] != b"\x00\x01" or i < 10 or set(em[2:i]) != {0xFF}:
            return False
        alg, digest = parse(em[i + 1:]).kids()[:2]
        params = alg.kids()
    except (ValueError, IndexError):
        return False
    return HASHES.get(oid(params[0])) == h and all(x.is_(5) for x in params[1:]) and digest.val == hashlib.new(h, data).digest()


def mgf1(seed, length, h):
    out, c = b"", 0
    while len(out) < length:
        out += hashlib.new(h, seed + c.to_bytes(4, "big")).digest()
        c += 1
    return out[:length]


def rsa_pss_verify(n, e, params, data, signature):
    h, mgf_h, salt_len = "sha1", "sha1", 20
    for p in (params.kids() if params is not None else []):
        inner = p.kids()[0]
        if p.cls == 2 and p.num == 0:
            h = HASHES[oid(inner.kids()[0])]
        elif p.cls == 2 and p.num == 1:
            mgf = inner.kids()
            if oid(mgf[0]) != MGF1:
                return False
            mgf_h = HASHES[oid(mgf[1].kids()[0])]
        elif p.cls == 2 and p.num == 2:
            salt_len = integer(inner)
    em_bits = n.bit_length() - 1
    em_len = (em_bits + 7) // 8
    s = int.from_bytes(signature, "big")
    if s >= n:
        return False
    m = pow(s, e, n)
    if m.bit_length() > em_bits:
        return False
    em = m.to_bytes(em_len, "big")
    m_hash = hashlib.new(h, data).digest()
    h_len = len(m_hash)
    if em_len < h_len + salt_len + 2 or em[-1] != 0xBC:
        return False
    masked, hh = em[:em_len - h_len - 1], em[em_len - h_len - 1:-1]
    zero_bits = 8 * em_len - em_bits
    if zero_bits and masked[0] >> (8 - zero_bits):
        return False
    db = bytearray(a ^ b for a, b in zip(masked, mgf1(hh, len(masked), mgf_h)))
    if zero_bits:
        db[0] &= 0xFF >> zero_bits
    ps = em_len - h_len - salt_len - 2
    if any(db[:ps]) or db[ps] != 1:
        return False
    salt = bytes(db[len(db) - salt_len:]) if salt_len else b""
    return hashlib.new(h, b"\x00" * 8 + m_hash + salt).digest() == hh


def public_key(spki):
    alg, key = spki.kids()[:2]
    parts = alg.kids()
    kind = oid(parts[0])
    if kind in (RSA_KEY, RSA_PSS):
        n, e = parse(bits(key)).kids()[:2]
        return "rsa", (integer(n), integer(e))
    if kind == EC_KEY:
        curve = CURVES.get(oid(parts[1])) if len(parts) > 1 and parts[1].is_(6) else None
        if curve is None:
            raise ValueError("curva ellittica non supportata")
        return "ec", (curve, decode_point(curve, bits(key)))
    raise ValueError("tipo di chiave non supportato: " + kind)


def key_label(spki):
    kind, k = public_key(spki)
    return "RSA %d bit" % k[0].bit_length() if kind == "rsa" else "ECDSA " + k[0][0]


def verify_signature(spki, alg, data, signature, default_hash=None):
    """Firma `signature` su `data` con la chiave `spki` secondo l'AlgorithmIdentifier `alg` (False se non torna)."""
    try:
        parts = alg.kids()
        a = oid(parts[0])
        kind, key = public_key(spki)
        if a == RSA_PSS:
            return kind == "rsa" and rsa_pss_verify(key[0], key[1], parts[1] if len(parts) > 1 else None, data, signature)
        if a in SIG_ALGS:
            want, h = SIG_ALGS[a]
        elif a in (RSA_KEY, EC_KEY):  # CMS: algoritmo della chiave, hash da digestAlgorithm
            want, h = ("rsa" if a == RSA_KEY else "ec"), default_hash
        else:
            return False
        if h is None or want != kind:
            return False
        if kind == "rsa":
            return rsa_pkcs1_verify(key[0], key[1], h, data, signature)
        return ecdsa_verify(key[0], key[1], hashlib.new(h, data).digest(), signature)
    except (ValueError, IndexError, KeyError):
        return False


def verify_with_hash(spki, h, data, signature):
    """Firma senza AlgorithmIdentifier (manifest del telefono): ECDSA o RSA PKCS#1 v1.5 con l'hash dato."""
    try:
        kind, key = public_key(spki)
        if kind == "ec":
            return ecdsa_verify(key[0], key[1], hashlib.new(h, data).digest(), signature)
        return rsa_pkcs1_verify(key[0], key[1], h, data, signature)
    except (ValueError, IndexError):
        return False


# RIPEMD-160 in Python puro: hashlib lo offre solo se l'OpenSSL del sistema lo abilita (operazione rara nelle prove
# OpenTimestamps, ma prevista dal formato)
_RL = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15], [7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8],
       [3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12], [1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2],
       [4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13]]
_RR = [[5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12], [6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2],
       [15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13], [8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14],
       [12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11]]
_SL = [[11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8], [7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12],
       [11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5], [11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12],
       [9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6]]
_SR = [[8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6], [9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11],
       [9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5], [15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8],
       [8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11]]
_KL = [0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xA953FD4E]
_KR = [0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000]


def _rf(j, x, y, z):
    if j == 0:
        return x ^ y ^ z
    if j == 1:
        return (x & y) | (~x & z)
    if j == 2:
        return (x | ~y) ^ z
    if j == 3:
        return (x & z) | (y & ~z)
    return x ^ (y | ~z)


def _rol(x, n):
    x &= 0xFFFFFFFF
    return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFF


def ripemd160(data):
    try:
        return hashlib.new("ripemd160", data).digest()
    except ValueError:
        pass
    h = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0]
    msg = data + b"\x80" + b"\x00" * ((55 - len(data)) % 64) + (len(data) * 8).to_bytes(8, "little")
    for off in range(0, len(msg), 64):
        x = [int.from_bytes(msg[off + 4 * i:off + 4 * i + 4], "little") for i in range(16)]
        al, bl, cl, dl, el = h
        ar, br, cr, dr, er = h
        for j in range(5):
            for i in range(16):
                t = _rol(al + _rf(j, bl, cl, dl) + x[_RL[j][i]] + _KL[j], _SL[j][i]) + el
                al, el, dl, cl, bl = el, dl, _rol(cl, 10), bl, t & 0xFFFFFFFF
                t = _rol(ar + _rf(4 - j, br, cr, dr) + x[_RR[j][i]] + _KR[j], _SR[j][i]) + er
                ar, er, dr, cr, br = er, dr, _rol(cr, 10), br, t & 0xFFFFFFFF
        t = (h[1] + cl + dr) & 0xFFFFFFFF
        h[1] = (h[2] + dl + er) & 0xFFFFFFFF
        h[2] = (h[3] + el + ar) & 0xFFFFFFFF
        h[3] = (h[4] + al + br) & 0xFFFFFFFF
        h[4] = (h[0] + bl + cr) & 0xFFFFFFFF
        h[0] = t
    return b"".join(v.to_bytes(4, "little") for v in h)


# --- certificati X.509 -------------------------------------------------------------------------------------------

EKU_TIMESTAMPING, EKU_OCSP = "1.3.6.1.5.5.7.3.8", "1.3.6.1.5.5.7.3.9"
KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17"


class Cert:
    def __init__(self, der):
        self.der = bytes(der)
        tbs, self.sig_alg, sig = parse(self.der).kids()[:3]
        self.tbs = tbs.raw
        self.signature = bits(sig)
        f = tbs.kids()
        i = 1 if f[0].cls == 2 and f[0].num == 0 else 0
        self.version = integer(f[0].kids()[0]) + 1 if i else 1
        self.serial = integer(f[i])
        self.issuer, validity, self.subject, self.spki = f[i + 2], f[i + 3].kids(), f[i + 4], f[i + 5]
        self.not_before, self.not_after = parse_time(validity[0]), parse_time(validity[1])
        self.ext = {}
        for x in f[i + 6:]:
            if x.cls == 2 and x.num == 3:
                for e in x.kids()[0].kids():
                    parts = e.kids()
                    self.ext[oid(parts[0])] = (len(parts) == 3 and parts[1].val != b"\x00", parts[-1].val)
        self.sha256 = hashlib.sha256(self.der).hexdigest()

    @property
    def name(self):
        f = name_fields(self.subject)
        return f.get("CN") or f.get("O") or f.get("serialNumber") or "certificato %x" % self.serial

    def is_ca(self):
        if self.version < 3:
            return True  # v1 senza estensioni: le vecchie radici
        bc = self.ext.get("2.5.29.19")
        if bc is not None:
            kids = parse(bc[1]).kids()
            return bool(kids) and kids[0].is_(1) and kids[0].val != b"\x00"
        ku = self.ext.get("2.5.29.15")
        return ku is not None and len(parse(ku[1]).val) > 1 and bool(parse(ku[1]).val[1] & 0x04)

    def eku(self):
        e = self.ext.get("2.5.29.37")
        return {oid(x) for x in parse(e[1]).kids()} if e else set()

    def ski(self):
        e = self.ext.get("2.5.29.14")
        return parse(e[1]).val if e else None

    def valid_at(self, t):
        return self.not_before <= t <= self.not_after


def signs(issuer, cert):
    """Vero se la chiave di `issuer` ha firmato `cert`."""
    return verify_signature(issuer.spki, cert.sig_alg, cert.tbs, cert.signature)


def build_path(cert, pool, anchors):
    """Percorso dal certificato a una radice fidata, collegando per firma (prima i candidati con il nome giusto)."""
    path = [cert]
    while True:
        cur = path[-1]
        # arrivati a una radice fidata (anche nella copia firmata da un'altra autorità che alcune TSA mettono nel
        # token: stessa chiave e stesso nome): il percorso finisce con la radice fissata
        anchor = next((a for a in anchors if a.spki.raw == cur.spki.raw and a.subject.raw == cur.subject.raw), None)
        if anchor is not None:
            path[-1] = anchor
            return path
        if len(path) > 8:
            raise ValueError("catena di certificati troppo lunga")
        seen = {c.der for c in path}
        cands = [c for c in anchors + pool if c.der not in seen]
        cands.sort(key=lambda c: c.subject.raw != cur.issuer.raw)  # stabile: a parità, prima le radici
        issuer = next((c for c in cands if signs(c, cur)), None)
        if issuer is None:
            raise ValueError("nessuna autorità fidata ha firmato il certificato " + cur.name)
        path.append(issuer)


# --- marche RFC 3161 ---------------------------------------------------------------------------------------------

SIGNED_DATA, TST_INFO = "1.2.840.113549.1.7.2", "1.2.840.113549.1.9.16.1.4"
CONTENT_TYPE, MESSAGE_DIGEST = "1.2.840.113549.1.9.3", "1.2.840.113549.1.9.4"
SIGNING_CERT, SIGNING_CERT_V2 = "1.2.840.113549.1.9.16.2.12", "1.2.840.113549.1.9.16.2.47"
QC_STATEMENTS, QTST_STATEMENT = "1.3.6.1.5.5.7.1.3", "0.4.0.19422.1.1"


def verify_timestamp(tsr, expected_hex, trust):
    """Verifica un token RFC 3161 (risposta .tsr) su un'impronta SHA-256 attesa, con la catena fino a una radice tra i
    certificati `trust`. Solleva ValueError al primo problema; restituisce i dati della marca."""
    top = parse(tsr)
    k = top.kids()
    if k[0].is_(16):  # TimeStampResp: stato e token
        if integer(k[0].kids()[0]) not in (0, 1):
            raise ValueError("la risposta della TSA è un rifiuto")
        if len(k) < 2:
            raise ValueError("risposta senza token")
        token = k[1]
    else:
        token = top
    ct, content = token.kids()[:2]
    if oid(ct) != SIGNED_DATA:
        raise ValueError("il token non è un SignedData CMS")
    sd = content.kids()[0].kids()
    eci = sd[2].kids()
    if oid(eci[0]) != TST_INFO:
        raise ValueError("il contenuto firmato non è un TSTInfo")
    econtent = eci[1].kids()[0].val
    certs, infos = [], []
    for x in sd[3:]:
        if x.cls == 2 and x.num == 0:
            certs = [Cert(c.raw) for c in x.kids() if c.is_(16)]
        elif x.is_(17):
            infos = x.kids()
    if len(infos) != 1:
        raise ValueError("il token deve avere esattamente un firmatario")
    si = infos[0].kids()
    sid, digest_alg = si[1], HASHES.get(oid(si[2].kids()[0]))
    if digest_alg is None:
        raise ValueError("algoritmo di hash del firmatario non supportato")
    idx = 3
    if not (si[idx].cls == 2 and si[idx].num == 0):
        raise ValueError("attributi firmati assenti")
    signed_attrs = si[idx]
    sig_alg, signature = si[idx + 1], si[idx + 2].val
    attrs = {}
    for a in signed_attrs.kids():
        t, values = a.kids()[:2]
        attrs[oid(t)] = values.kids()
    if CONTENT_TYPE not in attrs or oid(attrs[CONTENT_TYPE][0]) != TST_INFO:
        raise ValueError("attributo contentType assente o diverso da TSTInfo")
    if MESSAGE_DIGEST not in attrs or attrs[MESSAGE_DIGEST][0].val != hashlib.new(digest_alg, econtent).digest():
        raise ValueError("messageDigest diverso dall'impronta del contenuto firmato")

    pool = certs + trust
    if sid.is_(16):
        iss, ser = sid.kids()[:2]
        signer = next((c for c in pool if c.serial == integer(ser) and c.issuer.raw == iss.raw), None)
    else:
        signer = next((c for c in pool if c.ski() == sid.val), None)
    if signer is None:
        raise ValueError("certificato del firmatario non trovato")
    if not verify_signature(signer.spki, sig_alg, b"\x31" + signed_attrs.raw[1:], signature, digest_alg):
        raise ValueError("firma della marca non valida")
    if SIGNING_CERT_V2 in attrs:
        cid = attrs[SIGNING_CERT_V2][0].kids()[0].kids()[0].kids()
        if cid[0].is_(16):
            h, cert_hash = HASHES[oid(cid[0].kids()[0])], cid[1].val
        else:
            h, cert_hash = "sha256", cid[0].val
    elif SIGNING_CERT in attrs:
        h, cert_hash = "sha1", attrs[SIGNING_CERT][0].kids()[0].kids()[0].kids()[0].val
    else:
        raise ValueError("manca l'attributo signingCertificate")
    if hashlib.new(h, signer.der).digest() != cert_hash:
        raise ValueError("signingCertificate non corrisponde al certificato del firmatario")

    tst = parse(econtent).kids()
    imprint = tst[2].kids()
    if HASHES.get(oid(imprint[0].kids()[0])) != "sha256" or imprint[1].val.hex() != expected_hex:
        raise ValueError("l'impronta marcata non è quella attesa")
    gen_time = parse_time(tst[4])
    accuracy, qtst = None, False
    for x in tst[5:]:
        if x.is_(16):
            ms = 0
            for a in x.kids():
                ms += integer(a) * 1000 if a.is_(2) else int.from_bytes(a.val, "big") if a.num == 0 else 0
            accuracy = ms
        elif x.cls == 2 and x.num == 1:
            for e in x.kids():
                parts = e.kids()
                if oid(parts[0]) == QC_STATEMENTS:
                    qtst |= any(oid(st.kids()[0]) == QTST_STATEMENT for st in parse(parts[-1].val).kids())

    if EKU_TIMESTAMPING not in signer.eku():
        raise ValueError("il certificato del firmatario non è abilitato alle marche temporali")
    # radici: i certificati del file di fiducia che nessun altro del file ha firmato (le autofirmate, e una radice
    # firmata da un'autorità esterna al file); gli altri sono intermedi
    anchors = [c for c in trust if not any(o.der != c.der and signs(o, c) for o in trust)]
    path = build_path(signer, pool, anchors)
    for c in path:
        if not c.valid_at(gen_time):
            raise ValueError("certificato %s non valido all'ora della marca" % c.name)
    for c in path[1:]:
        if not c.is_ca():
            raise ValueError("il certificato %s non è un'autorità di certificazione" % c.name)
    return {"gen_time": gen_time, "accuracy_ms": accuracy, "policy": oid(tst[1]), "serial": integer(tst[3]),
            "qtst": qtst, "path": path}


def check_ocsp(data, path, when, covered):
    resp = parse(data).kids()
    if integer(resp[0]) != 0:
        return [("ATTN", "risposta OCSP con stato di errore %d" % integer(resp[0]))]
    body = resp[1].kids()[0].kids()
    if oid(body[0]) != "1.3.6.1.5.5.7.48.1.1":
        return [("ATTN", "risposta OCSP di tipo non previsto")]
    basic = parse(body[1].val).kids()
    tbs, alg, sig = basic[0], basic[1], bits(basic[2])
    extra = [Cert(c.raw) for c in basic[3].kids()[0].kids()] if len(basic) > 3 else []
    f = tbs.kids()
    i = 1 if f[0].cls == 2 and f[0].num == 0 else 0
    out = []
    for single in f[i + 2].kids():
        s = single.kids()
        cid = s[0].kids()
        h = HASHES.get(oid(cid[0].kids()[0]))
        for j, c in enumerate(path[:-1]):
            issuer = path[j + 1]
            if h is None or c.serial != integer(cid[3]) or cid[2].val != hashlib.new(h, bits(issuer.spki.kids()[1])).digest():
                continue
            if verify_signature(issuer.spki, alg, tbs.raw, sig):
                responder = issuer.name
            else:  # responder delegato: firmato dall'emittente e abilitato a OCSP
                responder = next((r.name for r in extra if EKU_OCSP in r.eku() and signs(issuer, r)
                                  and verify_signature(r.spki, alg, tbs.raw, sig)), None)
            if responder is None:
                out.append(("ERR", "OCSP per %s: firma della risposta non valida o responder non autorizzato" % c.name))
                continue
            status, this_update = s[1], parse_time(s[2])
            next_update = next((parse_time(x.kids()[0]) for x in s[3:] if x.cls == 2 and x.num == 0), None)
            covered.add(c.der)
            if status.cls == 2 and status.num == 0:
                level, period = validity(this_update, next_update, when)
                out.append((level, "OCSP: %s non revocato (risposta firmata da %s, %s)" % (c.name, responder, period)))
            elif status.cls == 2 and status.num == 1:
                revoked = parse_time(status.kids()[0])
                out.append(("ERR" if revoked <= when else "ATTN", "OCSP: %s revocato il %s" % (c.name, fmt(revoked))))
            else:
                out.append(("ATTN", "OCSP: %s sconosciuto al responder" % c.name))
    return out


def check_crl(data, path, when, covered):
    tbs, alg, sig = parse(data).kids()[:3]
    f = tbs.kids()
    i = 1 if f[0].is_(2) else 0
    issuer_name, this_update = f[i + 1], parse_time(f[i + 2])
    next_update = parse_time(f[i + 3]) if len(f) > i + 3 and f[i + 3].num in (23, 24) and f[i + 3].cls == 0 else None
    revoked = {}
    for x in f[i + 3:]:
        if x.is_(16):
            for entry in x.kids():
                e = entry.kids()
                revoked[integer(e[0])] = parse_time(e[1])
    out = []
    for j, c in enumerate(path[:-1]):
        issuer = path[j + 1]
        if c.issuer.raw != issuer_name.raw:
            continue
        if not verify_signature(issuer.spki, alg, tbs.raw, bits(sig)):
            out.append(("ERR", "CRL di %s: firma non valida" % issuer.name))
            continue
        covered.add(c.der)
        if c.serial in revoked:
            t = revoked[c.serial]
            out.append(("ERR" if t <= when else "ATTN", "CRL: %s revocato il %s" % (c.name, fmt(t))))
        else:
            level, period = validity(this_update, next_update, when)
            out.append((level, "CRL di %s: %s non revocato (%s)" % (issuer.name, c.name, period)))
    return out


def check_revocation(path, when, files):
    out, covered = [], set()
    for f in files:
        try:
            out += (check_ocsp if "_ocsp" in f.name else check_crl)(f.read_bytes(), path, when, covered)
        except (ValueError, IndexError, KeyError) as e:
            out.append(("ATTN", "%s non leggibile (%s)" % (f.name, e)))
    for c in path[:-1]:
        if c.der not in covered:
            out.append(("ATTN", "nessuna risposta OCSP o CRL conservata per %s" % c.name))
    return out


TSL = "{http://uri.etsi.org/02231/v2#}"
QTST = "http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST"


def _iso(t):
    return datetime.fromisoformat(t.strip().replace("Z", "+00:00"))


def tl_status(xml_bytes, path, when):
    """Servizio QTST della Trusted List la cui identità è nel percorso della marca, con lo stato in vigore all'ora
    della marca (anche dallo storico del servizio). None se nessun servizio corrisponde."""
    root = ET.fromstring(xml_bytes)
    hashes = {c.sha256 for c in path}
    best = None
    for svc in root.iter(TSL + "TSPService"):
        info = svc.find(TSL + "ServiceInformation")
        if info is None or (info.findtext(TSL + "ServiceTypeIdentifier") or "").strip() != QTST:
            continue
        ids = {hashlib.sha256(base64.b64decode("".join(x.text.split()))).hexdigest()
               for x in info.iter(TSL + "X509Certificate") if x.text and x.text.strip()}
        if not ids & hashes:
            continue
        states = [(info.findtext(TSL + "StatusStartingTime"), info.findtext(TSL + "ServiceStatus"))]
        states += [(h.findtext(TSL + "StatusStartingTime"), h.findtext(TSL + "ServiceStatus"))
                   for h in svc.iter(TSL + "ServiceHistoryInstance")]
        valid = sorted((_iso(t), s.strip()) for t, s in states if t and s and _iso(t) <= when)
        if not valid:
            continue
        since, status = valid[-1]
        name = info.findtext(TSL + "ServiceName/" + TSL + "Name") or "?"
        candidate = (status.endswith("/granted"), since, name.strip(), status.rsplit("/", 1)[-1])
        if best is None or candidate[:2] > best[:2]:
            best = candidate
    if best is None:
        return None
    return {"granted": best[0], "since": best[1], "service": best[2], "status": best[3],
            "sequence": root.findtext(".//" + TSL + "TSLSequenceNumber"),
            "issued": root.findtext(".//" + TSL + "ListIssueDateTime")}


# --- OpenTimestamps e Bitcoin ------------------------------------------------------------------------------------

OTS_MAGIC = b"\x00OpenTimestamps\x00\x00Proof\x00\xbf\x89\xe2\xe8\x84\xe8\x92\x94"
OTS_BITCOIN, OTS_PENDING = bytes.fromhex("0588960d73d71901"), bytes.fromhex("83dfe30d2ef90c8e")


class _Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, n):
        if self.p + n > len(self.d):
            raise ValueError("prova OTS troncata")
        out = self.d[self.p:self.p + n]
        self.p += n
        return out

    def byte(self):
        return self.take(1)[0]

    def varuint(self):
        v, shift = 0, 0
        while True:
            b = self.byte()
            v |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return v

    def varbytes(self):
        n = self.varuint()
        if n > 8192:
            raise ValueError("campo troppo lungo nella prova OTS")
        return self.take(n)


def ots_parse(data):
    """(impronta di partenza, [(messaggio, "bitcoin"|"pending"|"altro", altezza|uri|tag)]) di una prova .ots: ogni
    attestazione con il messaggio ricalcolato applicando le operazioni del percorso."""
    r = _Reader(data)
    if r.take(len(OTS_MAGIC)) != OTS_MAGIC:
        raise ValueError("non è una prova OpenTimestamps")
    if r.varuint() != 1:
        raise ValueError("versione della prova OpenTimestamps non supportata")
    if r.byte() != 0x08:
        raise ValueError("la prova non parte da un'impronta SHA-256")
    digest = r.take(32)
    out = []
    sys.setrecursionlimit(max(sys.getrecursionlimit(), 5000))
    _ots_walk(r, digest, out, 0)
    if r.p != len(data):
        raise ValueError("byte in eccesso nella prova OTS")
    return digest, out


def _ots_walk(r, msg, out, depth):
    if depth > 1000:
        raise ValueError("prova OTS troppo profonda")
    tag = r.byte()
    while tag == 0xFF:
        _ots_branch(r, msg, r.byte(), out, depth)
        tag = r.byte()
    _ots_branch(r, msg, tag, out, depth)


def _ots_branch(r, msg, tag, out, depth):
    if tag == 0x00:
        kind, payload = r.take(8), _Reader(r.varbytes())
        if kind == OTS_BITCOIN:
            out.append((msg, "bitcoin", payload.varuint()))
        elif kind == OTS_PENDING:
            out.append((msg, "pending", payload.varbytes().decode("utf-8", "replace")))
        else:
            out.append((msg, "altro", kind.hex()))
        return
    if tag == 0xF0:
        msg = msg + r.varbytes()
    elif tag == 0xF1:
        msg = r.varbytes() + msg
    elif tag == 0xF2:
        msg = msg[::-1]
    elif tag == 0xF3:
        msg = msg.hex().encode()
    elif tag == 0x02:
        msg = hashlib.sha1(msg).digest()
    elif tag == 0x03:
        msg = ripemd160(msg)
    elif tag == 0x08:
        msg = hashlib.sha256(msg).digest()
    else:
        raise ValueError("operazione OTS non supportata (0x%02x)" % tag)
    if len(msg) > 4096:
        raise ValueError("messaggio troppo lungo nella prova OTS")
    _ots_walk(r, msg, out, depth + 1)


def check_block_header(header, merkle_root, block_hash=None):
    """Intestazione di un blocco Bitcoin (80 byte) che contiene `merkle_root`, con proof-of-work valido. Restituisce
    (hash del blocco, ora del blocco, difficoltà)."""
    if len(header) != 80:
        raise ValueError("intestazione del blocco di lunghezza errata")
    h = hashlib.sha256(hashlib.sha256(header).digest()).digest()
    if block_hash and h[::-1].hex() != block_hash:
        raise ValueError("l'intestazione non è quella del blocco indicato")
    if header[36:68] != merkle_root:
        raise ValueError("la radice di Merkle del blocco non è quella della prova")
    nbits = int.from_bytes(header[72:76], "little")
    target = (nbits & 0x7FFFFF) << (8 * ((nbits >> 24) - 3))
    if target <= 0 or int.from_bytes(h, "little") > target:
        raise ValueError("proof-of-work del blocco non valido")
    when = datetime.fromtimestamp(int.from_bytes(header[68:72], "little"), UTC)
    return h[::-1].hex(), when, (0xFFFF << 208) / target


EXPLORERS = ["https://blockstream.info/api", "https://mempool.space/api"]

# fonti ufficiali: chi legge lo script vede da dove arriva ogni radice di fiducia
FONTI = {
    "google": "https://android.googleapis.com/attestation/root",  # radici dell'attestazione (Google)
    "revoche": "https://android.googleapis.com/attestation/status",  # chiavi di attestazione revocate (Google)
    "lotl": "https://ec.europa.eu/tools/lotl/eu-lotl.xml",  # Trusted List europea (Commissione)
    # radici dei prestatori non qualificati, dai loro siti
    "digicert": "https://cacerts.digicert.com/DigiCertTrustedRootG4.crt",
    "freetsa": "https://freetsa.org/files/cacert.pem",
    # sorgenti pubblici di Timbro: una release per ogni versione in produzione, con il tag timbro-<7 cifre>
    "sorgenti": "https://github.com/Zawardo1000/timbro-sorgenti",
}
TL_TERRITORY = {"accv": "ES", "sectigo-q": "ES"}  # Stato che vigila sul prestatore qualificato


def _get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "timbro-verifica"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


class Sources:
    """Fonti ufficiali in rete, ciascuna scaricata una volta sola. Spente con --offline."""

    def __init__(self, enabled):
        self.enabled, self._cache = enabled, {}

    def get(self, url):
        if url not in self._cache:
            self._cache[url] = _get(url)
        return self._cache[url]

    def certs(self, url):
        data = self.get(url)
        if b"-----BEGIN" in data:
            return [Cert(d) for d in pem_blocks(data.decode("ascii", "replace"))]
        return [Cert(data)]

    def google_roots(self):
        return [Cert(d) for item in json.loads(self.get(FONTI["google"])) for d in pem_blocks(item)]

    def trusted_list(self, territory):
        """(XML, indirizzo) della Trusted List nazionale, dall'indirizzo che la lista europea indica."""
        lotl = ET.fromstring(self.get(FONTI["lotl"]))
        for ptr in lotl.iter(TSL + "OtherTSLPointer"):
            mimes = [e.text for e in ptr.iter() if e.tag.endswith("MimeType")]
            if ptr.findtext(".//" + TSL + "SchemeTerritory") == territory and "application/vnd.etsi.tsl+xml" in mimes:
                url = ptr.findtext(TSL + "TSLLocation").strip()
                return self.get(url), url
        raise ValueError("nessuna Trusted List per %s nella lista europea" % territory)


def online_block(height):
    """(hash, intestazione) del blocco a quell'altezza secondo due explorer indipendenti, che devono coincidere."""
    hashes = {}
    for e in EXPLORERS:
        try:
            hashes[e] = _get("%s/block-height/%d" % (e, height)).decode().strip()
        except Exception as ex:  # rete: si dice e si continua con l'altro
            print("     %s non raggiungibile: %s" % (e, ex))
    if not hashes:
        raise ValueError("nessun explorer raggiungibile")
    if len(set(hashes.values())) != 1:
        raise ValueError("gli explorer indicano blocchi diversi all'altezza %d" % height)
    block_hash = next(iter(hashes.values()))
    header = bytes.fromhex(_get("%s/block/%s/header" % (next(iter(hashes)), block_hash)).decode().strip())
    return block_hash, header, len(hashes)


# --- attestazione hardware ---------------------------------------------------------------------------------------

LEVELS = {0: "software", 1: "TEE", 2: "StrongBox"}
BOOT = {0: "verificato", 1: "autofirmato", 2: "non verificato", 3: "fallito"}


def read_key_description(value):
    kd = parse(value).kids()

    def auth(node):
        return {c.num: c.kids()[0] for c in node.kids() if c.cls == 2}

    sw, hw = auth(kd[6]), auth(kd[7])
    out = {"level": LEVELS.get(integer(kd[1]), "?"), "challenge": kd[4].val, "locked": None, "boot": None,
           "os_version": None, "patch": None, "apps": [], "digests": []}
    if 704 in hw:
        rot = hw[704].kids()
        out["locked"] = rot[1].val != b"\x00"
        out["boot"] = BOOT.get(integer(rot[2]), "?")
    for tag, key in ((705, "os_version"), (706, "patch")):
        if tag in hw:
            out[key] = integer(hw[tag])
    aid = sw.get(709) or hw.get(709)
    if aid is not None:
        parts = parse(aid.val).kids()
        out["apps"] = [(p.kids()[0].val.decode("utf-8", "replace"), integer(p.kids()[1])) for p in parts[0].kids()]
        out["digests"] = [d.val.hex() for d in parts[1].kids()] if len(parts) > 1 else []
    return out


def verify_attestation(chain_certs, roots):
    """Catena di Android Key Attestation (foglia → radice) collegata per firma fino a una radice di Google (stessa
    chiave pubblica). Restituisce (problemi, radice usata)."""
    problems = []
    if len(chain_certs) < 2:
        return ["catena troppo corta"], None
    for i in range(len(chain_certs) - 1):
        if not signs(chain_certs[i + 1], chain_certs[i]):
            problems.append("il certificato %d della catena non è firmato dal successivo" % i)
    last = chain_certs[-1]
    root = next((r for r in roots if r.spki.raw == last.spki.raw), None)
    if root is None:
        problems.append("la catena non termina con una radice di Google")
    elif not signs(root, last):
        problems.append("la radice della catena non è firmata con la chiave di Google")
    for i, c in enumerate(chain_certs[1:], 1):
        if KEY_DESCRIPTION in c.ext:
            problems.append("il certificato %d ha un'attestazione fuori dalla foglia (catena estesa)" % i)
        if not c.is_ca():
            problems.append("il certificato %d non è un'autorità che può firmare certificati" % i)
    if KEY_DESCRIPTION not in chain_certs[0].ext:
        problems.append("la foglia non contiene l'attestazione (KeyDescription)")
    return problems, root


# --- verifica del pacchetto --------------------------------------------------------------------------------------


class Report:
    def __init__(self):
        self.errors, self.warnings, self.pending = 0, [], 0

    def ok(self, msg):
        print("OK   " + msg)

    def err(self, msg):
        self.errors += 1
        print("ERR  " + msg)

    def attn(self, msg):
        self.warnings.append(msg)
        print("ATTN " + msg)

    def wait(self, msg):
        self.pending += 1
        print("--   " + msg)

    def info(self, msg):
        print("     " + msg)

    def check(self, cond, msg):
        (self.ok if cond else self.err)(msg)
        return cond

    def emit(self, level, msg):
        {"OK": self.ok, "ERR": self.err, "ATTN": self.attn}[level](msg)

    def section(self, title):
        print("\n" + title)


def sha(b):
    return hashlib.sha256(b).digest()


def verify_package(here, online=True):
    rep = Report()
    src = Sources(online)
    rec = json.loads((here / "record.json").read_text(encoding="utf-8"))
    t = rec["timbratura"]
    print("Pacchetto di prova Timbro: timbratura n. %s (%s), ricevuta dal server il %s"
          % (t["id"], "entrata" if t["tipo"] == "in" else "uscita", t["ricevuta_dal_server"]))
    print("Radici di fiducia: " + ("dalle fonti ufficiali in rete (Google, Trusted List europea, siti dei prestatori)"
                                   if online else "dal pacchetto, senza rete (--offline): confronta le impronte stampate"))

    rep.section("1. Impronte e catena dei record")
    video = here / "video.mp4"
    if video.exists():
        rep.check(sha(video.read_bytes()).hex() == t["sha256_video"], "impronta del video = sha256_video")
    else:
        rep.wait("video non incluso (non più conservato): restano impronta, firma e marche")
    manifest = (here / "manifest.json").read_bytes()
    sig = (here / "manifest.sig").read_bytes()
    key_der = pem_blocks((here / "device_pubkey.pem").read_text())[0]
    rep.check(json.loads(manifest)["sha256"] == t["sha256_video"], "il manifest dichiara la stessa impronta del video")
    links = rec["catena_fino_alla_testa_del_giorno"]
    first = links[0]
    rep.check((sha(manifest).hex(), sha(sig).hex(), sha(key_der).hex())
              == (first["sha256_manifest"], first["sha256_signature"], first["sha256_device_key_der"]),
              "impronte di manifest, firma e chiave = primo anello")
    prev = first["prev_chain_hash"]
    for link in links:
        rep.check(link["prev_chain_hash"] == prev, "anello %s: collegato al precedente" % link["id"])
        h = hashlib.sha256(bytes.fromhex(link["prev_chain_hash"]))
        for k in ("sha256_manifest", "sha256_signature", "sha256_device_key_der"):
            h.update(bytes.fromhex(link[k]))
        h.update(link["server_received_at"].encode("utf-8"))
        rep.check(h.hexdigest() == link["chain_hash"], "anello %s: chain_hash ricalcolato" % link["id"])
        prev = link["chain_hash"]
    rep.check(first["chain_hash"] == t["chain_hash"], "chain_hash della timbratura = primo anello")
    head = prev

    rep.section("2. Firma del telefono")
    spki = parse(key_der)
    if rep.check(verify_with_hash(spki, "sha256", manifest, sig), "manifest.json firmato dalla chiave del telefono"):
        rep.info("chiave %s, impronta SHA-256 %s" % (key_label(spki), sha(key_der).hex()))

    chain_file = here / "attestazione" / "catena.pem"
    att_hash = sha(chain_file.read_bytes()).hex() if chain_file.exists() else None
    if chain_file.exists():
        rep.section("3. Attestazione hardware del telefono")
        _attestation(rep, here, rec, manifest, src)

    rep.section("4. Marche temporali RFC 3161")
    expected = {"video": t["sha256_video"], "record": t["chain_hash"], "chain": head, "attest": att_hash}
    tls = [p.read_bytes() for p in sorted((here / "trusted-list").glob("*.xml"))] if (here / "trusted-list").exists() else []
    marche = rec.get("marche", [])
    for m in marche:
        rep.check(m["impronta_marcata"] == expected[m["oggetto"]], "marca %s: impronta marcata corretta" % m["file"])
    stamp_times = {}
    for m in marche:
        if m["tipo"] == "rfc3161" and m["stato"] == "complete":
            r = _rfc3161(rep, here, m, expected[m["oggetto"]], tls, src)
            if r is not None:
                stamp_times.setdefault(m["oggetto"], []).append(r["gen_time"])

    rep.section("5. OpenTimestamps (blockchain Bitcoin)")
    for m in marche:
        if m["tipo"] == "ots":
            _ots(rep, here, m, expected[m["oggetto"]], src.enabled)

    rep.section("6. Coerenza delle ore")
    _time_coherence(rep, t, json.loads(manifest), stamp_times)

    rep.section("7. Provenienza del codice")
    _provenance(rep, rec.get("provenienza") or {}, src)

    print("\n" +("TUTTO VERIFICATO" if rep.errors == 0 else "CI SONO ERRORI (%d)" % rep.errors))
    if rep.warnings:
        print("Righe ATTN: %d avvisi, da leggere (proprietà del telefono, informazioni di revoca, Trusted List)."
              % len(dict.fromkeys(rep.warnings)))
    if rep.pending:
        print("Righe --: elementi non ancora disponibili o da confermare (per esempio prove Bitcoin in attesa).")
    if not online:
        print("Senza --offline lo script prende radici e stati dalle fonti ufficiali e controlla i blocchi Bitcoin con "
              "due explorer.")
    return rep


def _attestation(rep, here, rec, manifest, src):
    att = rec.get("attestazione") or {}
    folder = here / "attestazione"
    try:
        chain = [Cert(d) for d in pem_blocks((folder / "catena.pem").read_text())]
        roots = [Cert(d) for d in pem_blocks((folder / "radici_google.pem").read_text())]
        kd = read_key_description(chain[0].ext[KEY_DESCRIPTION][1])
    except (OSError, ValueError, IndexError, KeyError) as e:
        rep.err("attestazione illeggibile (%s)" % e)
        return
    if (folder / "foglia.pem").exists():
        leaf = pem_blocks((folder / "foglia.pem").read_text())
        rep.check(leaf[:1] == [chain[0].der], "attestazione: foglia.pem = primo certificato di catena.pem")
    rep.check(kd["challenge"] == sha(manifest), "attestazione: challenge = SHA-256 di manifest.json")
    source = "radici di Google nel pacchetto"
    if src.enabled:
        try:
            roots = src.google_roots()
            source = "radici pubblicate ora da Google (%s)" % FONTI["google"]
        except Exception as e:
            rep.attn("radici di Google non scaricate (%s): uso quelle del pacchetto" % e)
    problems, root = verify_attestation(chain, roots)
    for p in problems:
        rep.err("attestazione: " + p)
    if not problems:
        rep.ok("attestazione: catena di %d certificati firmata fino a una delle %s" % (len(chain), source))
        rep.info("radice: %s, chiave %s, impronta SHA-256 della chiave %s"
                 % (root.name, key_label(root.spki), sha(root.spki.raw).hex()))
        rep.info("(date dei certificati non controllate: quando esisteva lo provano le marche sulla catena)")
    v, patch = kd["os_version"], kd["patch"]
    android = "%d.%d.%d" % (v // 10000, v // 100 % 100, v % 100) if v else "?"
    patch = "%d-%02d" % (patch // 100, patch % 100) if patch and patch > 9999 else str(patch or "?")
    rep.info("chiave in %s, bootloader %s, avvio %s, versione di Android %s, patch di sicurezza %s"
             % (kd["level"], "bloccato" if kd["locked"] else "SBLOCCATO" if kd["locked"] is False else "?",
                kd["boot"] or "?", android, patch))
    for name, version in kd["apps"]:
        rep.info("app %s, versione %d" % (name, version))
    for d in kd["digests"]:
        rep.info("certificato di firma dell'app: SHA-256 %s" % d)
    if not problems and len(chain) >= 2:
        if "serialNumber" in name_fields(chain[-2].subject):
            rep.attn("chiave di attestazione installata in fabbrica: vale meno di una emessa da Google, perché chiavi "
                     "di fabbrica di alcuni modelli sono trapelate (controlla la lista di revoca)")
        else:
            rep.ok("chiave di attestazione emessa da Google (Remote Key Provisioning, certificati brevi)")
    if kd["level"] not in ("TEE", "StrongBox"):
        rep.attn("chiave non protetta dall'hardware (%s)" % kd["level"])
    if kd["locked"] is not True or kd["boot"] != "verificato":
        rep.attn("bootloader non bloccato o avvio non verificato (o stato non garantito dall'hardware)")
    expected = att.get("app_attesa") or {}
    if expected.get("pacchetto"):
        rep.check(expected["pacchetto"] in [a for a, _ in kd["apps"]], "app = %s" % expected["pacchetto"])
    if expected.get("impronte_certificato_firma"):
        rep.check(bool(set(kd["digests"]) & set(expected["impronte_certificato_firma"])),
                  "firma dell'app = certificato di Google Play indicato in record.json (app_attesa)")
    for problem in (att.get("esito") or {}).get("problems", []):
        rep.attn("esito del server alla ricezione: " + problem)
    revoked = (att.get("lista_di_revoca_google") or {}).get("revocati")
    if revoked:
        rep.attn("Google ha revocato chiavi di questa catena (record.json, lista_di_revoca_google)")
    if src.enabled:
        try:
            status = json.loads(src.get(FONTI["revoche"]))
            entries = {k.lower() for k in status["entries"]}
            hits = ["%x" % c.serial for c in chain if "%x" % c.serial in entries]
            if hits:
                rep.attn("lista di revoca di Google di oggi: revocati %s" % ", ".join(hits))
            else:
                rep.ok("lista di revoca di Google di oggi: nessun certificato della catena è revocato")
        except Exception as e:
            rep.attn("lista di revoca di Google non scaricata (%s)" % e)


def _rfc3161(rep, here, m, expected_hex, tls, src):
    base = here / m["file"]
    label = "%s · %s%s" % (m["oggetto"], m["fornitore"], " (qualificata eIDAS)" if m.get("qualificata") else "")
    trust_file = here / "stamps" / ("%s_trust.pem" % m["fornitore"])
    try:
        trust = [Cert(d) for d in pem_blocks(trust_file.read_text())]
        r = verify_timestamp((base.parent / (base.name + ".tsr")).read_bytes(), expected_hex, trust)
    except (OSError, ValueError, IndexError, KeyError) as e:
        rep.err("marca %s: %s" % (label, e))
        return None
    path = r["path"]
    acc = " ± %d ms" % r["accuracy_ms"] if r["accuracy_ms"] else ""
    rep.ok("marca %s: firma e catena valide, %s%s" % (label, fmt(r["gen_time"]), acc))
    rep.info("firmata da %s · radice %s (SHA-256 %s)" % (path[0].name, path[-1].name, path[-1].sha256))
    if m.get("qualificata"):
        if r["qtst"]:
            rep.ok("marca %s: dichiarata qualificata nel token (esi4-qtstStatement-1)" % label)
        else:
            rep.err("marca %s: attesa qualificata ma il token non lo dichiara" % label)
        lists, where = [(x, "copia nel pacchetto") for x in tls], "nel pacchetto"
        if src.enabled:
            try:
                xml, url = src.trusted_list(TL_TERRITORY.get(m["fornitore"], "ES"))
                lists, where = [(xml, "scaricata ora da " + url)], "ufficiale"
            except Exception as e:
                rep.attn("marca %s: Trusted List ufficiale non scaricata (%s): uso la copia nel pacchetto" % (label, e))
        found = [(s, origin) for s, origin in ((tl_status(x, path, r["gen_time"]), o) for x, o in lists) if s]
        if not lists:
            rep.attn("marca %s: Trusted List non inclusa nel pacchetto" % label)
        elif not found:
            rep.err("marca %s: nessun servizio qualificato della Trusted List %s corrisponde al percorso" % (label, where))
        else:
            s, origin = max(found, key=lambda x: (x[0]["granted"], x[0]["since"]))
            rep.check(s["granted"], "marca %s: servizio «%s», stato %s dal %s all'ora della marca" % (
                label, s["service"], s["status"], s["since"].date().isoformat()))
            rep.info("Trusted List n. %s del %s, %s" % (s["sequence"], (s["issued"] or "?")[:10], origin))
    elif src.enabled:
        url = FONTI.get(m["fornitore"])
        if url is None:
            rep.attn("marca %s: nessuna fonte ufficiale nota per la radice, usata quella del pacchetto" % label)
        else:
            try:
                official = src.certs(url)
            except Exception as e:
                rep.attn("marca %s: radice ufficiale non scaricata (%s): usata quella del pacchetto" % (label, e))
            else:
                rep.check(any(c.spki.raw == path[-1].spki.raw for c in official),
                          "marca %s: radice %s uguale a quella pubblicata dal prestatore (%s)" % (label, path[-1].name, url))
    files = sorted(base.parent.glob(base.name + "_ocsp*.der")) + sorted(base.parent.glob(base.name + "_crl*.der"))
    for level, msg in check_revocation(path, r["gen_time"], files):
        rep.emit(level, "marca %s: %s" % (label, msg))
    return r


def _release(src, commit):
    """Release pubblica di quel commit su GitHub (dati dell'API), o None se non c'è."""
    repo = FONTI["sorgenti"].replace("https://github.com/", "")
    try:
        return json.loads(src.get("https://api.github.com/repos/%s/releases/tags/timbro-%s" % (repo, commit[:7])))
    except Exception:
        return None


def _provenance(rep, prov, src):
    """Versioni del codice e release pubbliche (docs/PROGETTO.md §25). Il confronto vale solo se lo script che lo fa
    è quello vero: chi non vuole fidarsi lo scarica dall'indirizzo stampato e confronta l'impronta da sé."""
    mine = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    rep.info("impronta SHA-256 di questo script: %s" % mine)
    app = prov.get("app") or {}
    server = (prov.get("server_alla_ricezione") or {}).get("commit")
    pkg = (prov.get("server_del_pacchetto") or {}).get("commit")
    rep.info("app che ha timbrato: %s; server alla ricezione: %s; server del pacchetto: %s"
             % (app.get("versione") or "?", (server or "non registrato")[:7], (pkg or "non registrato")[:7]))
    if not src.enabled:
        rep.wait("senza rete: release e script pubblicato non controllati (sorgenti: %s)" % FONTI["sorgenti"])
        return
    if pkg:
        url = "https://raw.githubusercontent.com/%s/timbro-%s/timbro/verifica_pacchetto.py" % (
            FONTI["sorgenti"].replace("https://github.com/", ""), pkg[:7])
        try:
            published = hashlib.sha256(src.get(url)).hexdigest()
        except Exception as e:
            rep.attn("script pubblicato non scaricato (%s)" % e)
        else:
            rep.check(published == mine, "questo script è identico a quello pubblicato per il server %s" % pkg[:7])
    else:
        rep.attn("versione del server del pacchetto non registrata: script non confrontabile")
    for label, commit in (("app", app.get("commit")), ("server alla ricezione", server)):
        if not commit:
            continue
        r = _release(src, commit)
        if r is None:
            rep.attn("release pubblica di %s timbro-%s non trovata" % (label, commit[:7]))
        else:
            rep.ok("release pubblica di %s: %s, pubblicata il %s%s" % (
                label, r.get("html_url", "?"), (r.get("published_at") or "?")[:10],
                ", immutabile" if r.get("immutable") else ""))


SENT_AT_ONCE_S, CLOCK_TOLERANCE_S, STAMP_DELAY_S = 120, 120, 600


def _when(value):
    try:
        dt = _iso(value)
    except (AttributeError, TypeError, ValueError):
        return None
    return dt if dt.tzinfo is not None else None


def _span(seconds):
    seconds = abs(seconds)
    return "%d s" % seconds if seconds < 120 else "%d min" % round(seconds / 60)


def _time_coherence(rep, t, manifest, stamp_times):
    """L'ora del telefono si sposta dalle impostazioni: la confermano la ricezione immediata o gli orologi di rete e
    satellitare che l'app scrive nel manifest firmato. Le marche, che nessuno controlla, devono arrivare poco dopo
    la ricezione dichiarata: se arrivano molto dopo, il prestatore era in ritardo o la catena è stata riscritta."""
    device, received = _when(manifest.get("device_time")), _when(t["ricevuta_dal_server"])
    if device is None or received is None:
        rep.attn("ora del telefono o di ricezione illeggibile")
        return
    rep.info("inizio della ripresa secondo il telefono: %s; ricevuta dal server: %s" % (fmt(device), fmt(received)))
    clocks = manifest.get("clocks") or {}
    confirmed, contradicting = [], []
    for key, label in (("gnss", "satellitare"), ("network", "di rete")):
        c = _when(clocks.get(key))
        if c is None:
            continue
        diff = (device - c).total_seconds()
        if abs(diff) <= CLOCK_TOLERANCE_S:
            confirmed.append(label)
            rep.ok("ora del telefono = orologio %s scritto nel manifest firmato (scarto %s)" % (label, _span(diff)))
        else:
            contradicting.append((label, c))
            rep.attn("ora del telefono diversa di %s dall'orologio %s scritto nel manifest firmato: orologio del "
                     "telefono spostato o sbagliato" % (_span(diff), label))
    if clocks.get("auto_time") is False:
        rep.attn("all'inizio della ripresa l'ora del telefono era impostata a mano, non in automatico")
    delay = (received - device).total_seconds()
    if delay < -300:
        rep.attn("ora del telefono avanti di %s sulla ricezione del server" % _span(delay))
    elif delay <= SENT_AT_ONCE_S:
        rep.ok("ricevuta dal server %s dopo l'inizio della ripresa: inviata subito" % _span(delay))
    elif confirmed:
        rep.ok("ricevuta %s dopo l'inizio della ripresa (inviata dalla coda): ora confermata dall'orologio %s"
               % (_span(delay), " e ".join(confirmed)))
    elif contradicting:
        label, c = contradicting[0]
        rep.info("secondo l'orologio %s la ripresa è iniziata il %s, %s prima della ricezione"
                 % (label, fmt(c), _span((received - c).total_seconds())))
    else:
        rep.attn("ricevuta %s dopo l'ora dichiarata dal telefono, senza un orologio che la confermi: vale l'ora di "
                 "ricezione" % _span(delay))
    for target, label in (("video", "sul video"), ("record", "sul record")):
        times = stamp_times.get(target)
        if not times:
            continue
        first = min(times)
        lag = (first - received).total_seconds()
        if (first - device).total_seconds() < -CLOCK_TOLERANCE_S:
            rep.attn("prima marca %s (%s) prima dell'ora dichiarata dal telefono: orologio del telefono avanti"
                     % (label, fmt(first)))
        if -CLOCK_TOLERANCE_S <= lag <= STAMP_DELAY_S:
            rep.ok("prima marca %s %s dopo la ricezione" % (label, _span(lag)))
        else:
            rep.attn("prima marca %s %s %s la ricezione dichiarata: prestatore in ritardo, oppure ora di ricezione o "
                     "catena non originali" % (label, _span(lag), "dopo" if lag > 0 else "prima"))
    verdict = t.get("ora_timbratura")
    if verdict:
        rep.info("conclusione del server: ora %s, %s" % (
            verdict.get("ora"), verdict.get("avviso") or ("confermata " + verdict["confermata"] if verdict.get("confermata")
                                                         else "non confermata")))


def _ots(rep, here, m, expected_hex, online):
    base = here / m["file"]
    label = m["oggetto"]
    try:
        digest, atts = ots_parse((base.parent / (base.name + ".ots")).read_bytes())
    except (OSError, ValueError) as e:
        rep.err("prova OTS %s: %s" % (label, e))
        return
    if not rep.check(digest.hex() == expected_hex, "prova OTS %s: parte dall'impronta attesa" % label):
        return
    blocks = [(msg, h) for msg, kind, h in atts if kind == "bitcoin"]
    if not blocks:
        calendars = sorted({v for _, kind, v in atts if kind == "pending"})
        rep.wait("prova OTS %s: in attesa di conferma su Bitcoin (%s). Si completa con `ots upgrade %s.ots` o "
                 "riscaricando il pacchetto più tardi" % (label, ", ".join(calendars) or "nessun calendar", m["file"]))
        return
    details = m.get("dettagli") or {}
    for msg, height in sorted(blocks, key=lambda x: x[1]):
        header_hex = details.get("block_header") if details.get("block_height") == height else None
        try:
            if header_hex:
                block_hash, when, diff = check_block_header(bytes.fromhex(header_hex), msg, details.get("block_hash"))
                rep.ok("prova OTS %s: blocco Bitcoin %d del %s, radice di Merkle e proof-of-work verificati"
                       % (label, height, fmt(when)))
                rep.info("hash del blocco %s, difficoltà %.3g" % (block_hash, diff))
                if diff < 1e12:
                    rep.attn("prova OTS %s: difficoltà troppo bassa per un blocco della rete Bitcoin" % label)
            if online:
                block_hash, header, sources = online_block(height)
                _, when, diff = check_block_header(header, msg, block_hash)
                rep.ok("prova OTS %s: blocco %d confermato da %d explorer (%s), radice di Merkle e proof-of-work "
                       "verificati" % (label, height, sources, fmt(when)))
            elif not header_hex:
                rep.wait("prova OTS %s: blocco %d, radice di Merkle %s (ordine dell'explorer): da confrontare con un "
                         "explorer, o lanciando lo script senza --offline" % (label, height, msg[::-1].hex()))
        except Exception as e:
            rep.err("prova OTS %s, blocco %d: %s" % (label, height, e))


def main(argv):
    try:  # una console che non sa stampare un carattere (Windows) non deve fermare la verifica
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    online = "--offline" not in argv
    dirs = [a for a in argv if not a.startswith("-")]
    here = Path(dirs[0]).resolve() if dirs else Path(__file__).resolve().parent
    rep = verify_package(here, online)
    return 0 if rep.errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

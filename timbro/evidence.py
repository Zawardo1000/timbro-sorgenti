"""Pacchetto di prova di una timbratura: zip autosufficiente, verificabile senza il server.

Contenuto:
  video.mp4                     originale byte per byte (se ancora conservato)
  manifest.json, manifest.sig   manifest firmato dal telefono (byte esatti) e firma DER
  device_pubkey.pem             chiave del telefono (+ device_attestation.pem se presente)
  record.json                   dati del record e anelli della catena fino alla testa marcata del giorno
  stamps/                       token RFC 3161 (.tsr + .tsq), prove OTS (.ots), OCSP/CRL, certificati
  trusted-list/                 ultima copia archiviata della Trusted List nazionale (se presente)
  verifica.py                   verifica completa di impronte, firme, catene e marche (solo libreria standard)
  VERIFICA.md                   istruzioni di verifica con strumenti standard

Gli zip si costruiscono una volta per versione del record in data_dir/evidence-cache (mai in /tmp):
una richiesta ripetuta non crea nuovi file, un errore a metà non lascia file parziali, e la
cancellazione di un video elimina anche i pacchetti che lo contenevano (drop_cache).
"""

import base64
import hashlib
import json
import os
import zipfile
from datetime import datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from timbro import services, versione
from timbro.config import Settings
from timbro.manifest import canonical_pem, public_key_der
from timbro.models import Device, Punch, Stamp
from timbro.stamping import tsa

LOCAL_TZ = ZoneInfo("Europe/Rome")
FORMAT = "timbro-evidence-4"


# restic (backup del server, --exclude-caches) salta le cartelle con questo file: gli zip contengono copie dei
# video, si ricostruiscono quando servono e non devono finire nel backup
CACHEDIR_TAG = "Signature: 8a477f597d28d172789f06886806bc55\n# Cache dei pacchetti di prova di Timbro: ricostruibile.\n"


def cache_dir(settings: Settings) -> Path:
    return settings.data_dir / "evidence-cache"


def _ensure_cache_dir(settings: Settings) -> Path:
    d = cache_dir(settings)
    d.mkdir(parents=True, exist_ok=True)
    tag = d / "CACHEDIR.TAG"
    if not tag.exists():
        tag.write_text(CACHEDIR_TAG, encoding="ascii")
    return d


def drop_cache(settings: Settings, punch_id: int) -> None:
    for f in cache_dir(settings).glob(f"{punch_id}-*.zip"):
        f.unlink(missing_ok=True)


def prune_cache(settings: Settings, max_age: timedelta = timedelta(days=1)) -> None:
    d = cache_dir(settings)
    if not d.exists():
        return
    limit = datetime.now().timestamp() - max_age.total_seconds()
    for f in d.iterdir():
        try:
            if f.name != "CACHEDIR.TAG" and f.stat().st_mtime < limit:
                f.unlink(missing_ok=True)
        except FileNotFoundError:
            continue  # rinominato o cancellato nel frattempo (.part, drop_cache)


def build(s: Session, punch_id: int, settings: Settings, _retry: bool = True) -> Path:
    """Percorso dello zip della timbratura (dalla cache se la versione non è cambiata).

    Letti i dati, la transazione si chiude: durante la scrittura dello zip (fino a 50 MB di video) nessuna
    connessione al database resta occupata. Se il video viene cancellato mentre lo zip si costruisce, lo zip si
    scarta e si rifà senza video: nessuna copia del video sopravvive alla cancellazione.
    """
    p = s.get(Punch, punch_id)
    device = s.get(Device, p.device_id)
    chain_links, day_stamps = _chain_to_day_head(s, p)
    stamps = [st for st in list(p.stamps) + day_stamps if st.status in ("complete", "upgrading") and st.token]
    video = settings.videos_dir / p.video_path if p.video_path and not p.video_deleted_at else None
    tl = _latest_tl(settings)
    wifi = services.wifi_summary(s, p)
    roots_pem = "".join(f.read_text() for f in sorted(settings.attestation_roots_dir.glob("*.pem")))
    revocation = _revocation(s, p)

    version = hashlib.sha256(
        json.dumps(
            [
                FORMAT,
                hashlib.sha256(VERIFY_SCRIPT.encode()).hexdigest(),  # uno script nuovo rifà i pacchetti in cache
                services.time_check(p).warning,
                p.chain_hash,
                bool(video and video.exists()),
                [(st.id, st.status, hashlib.sha256(st.token).hexdigest()) for st in stamps],
                [link["chain_hash"] for link in chain_links],
                hashlib.sha256(tl.read_bytes()).hexdigest() if tl else None,
                wifi,  # i conteggi cambiano con le timbrature nuove
                revocation,
            ]
        ).encode()
    ).hexdigest()[:16]
    d = _ensure_cache_dir(settings)
    out = d / f"{punch_id}-{version}.zip"
    if out.exists():
        return out
    s.commit()  # libera la connessione: gli oggetti restano caricati (expire_on_commit=False)

    tmp = out.with_suffix(f".{os.getpid()}.{id(s)}.part")
    try:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            has_video = False
            if video is not None:
                try:
                    z.write(video, "video.mp4", compress_type=zipfile.ZIP_STORED)
                    has_video = True
                except FileNotFoundError:
                    pass  # cancellato nel frattempo: il pacchetto resta valido senza video
            z.writestr("manifest.json", p.manifest_raw)
            z.writestr("manifest.sig", p.device_signature)
            z.writestr("device_pubkey.pem", canonical_pem(device.public_key_pem))  # i byte usati dalla catena
            if device.attestation_chain_pem:
                z.writestr("device_attestation.pem", device.attestation_chain_pem)
            z.writestr("record.json", json.dumps(_record(p, device, chain_links, stamps, wifi, revocation, settings), indent=2,
                                                 ensure_ascii=False))
            if p.attestation_pem:
                z.writestr("attestazione/catena.pem", p.attestation_pem)
                z.writestr("attestazione/foglia.pem", _first_pem(p.attestation_pem))
                z.writestr("attestazione/radici_google.pem", roots_pem)
            providers = set()
            for st in stamps:
                base = _stamp_base(st)
                if st.kind == "rfc3161":
                    z.writestr(f"{base}.tsr", st.token)
                    z.writestr(f"{base}.tsq", st.request)
                    providers.add(st.provider)
                    if st.revinfo_json:
                        rev = json.loads(st.revinfo_json)
                        for i, o in enumerate(rev.get("ocsp", [])):
                            z.writestr(f"{base}_ocsp{i}.der", base64.b64decode(o))
                        for i, c in enumerate(rev.get("crl", [])):
                            z.writestr(f"{base}_crl{i}.der", base64.b64decode(c))
                else:
                    z.writestr(f"{base}.ots", st.token)
            for prov in sorted(providers):
                pems = [c.dump() for c in tsa.root_certs(prov) + tsa.intermediate_certs(prov)]
                z.writestr(f"stamps/{prov}_trust.pem", "".join(_pem(der) for der in pems))
            if tl:
                z.write(tl, f"trusted-list/{tl.name}")
            z.writestr("verifica.py", VERIFY_SCRIPT)
            z.writestr("VERIFICA.md", _instructions(p, stamps, has_video, bool(p.attestation_pem), _provenance(p, settings)))
        os.replace(tmp, out)
    finally:
        tmp.unlink(missing_ok=True)
    if has_video and s.scalar(select(Punch.video_deleted_at).where(Punch.id == punch_id)) is not None:
        out.unlink(missing_ok=True)  # cancellato mentre si costruiva lo zip
        s.commit()
        if _retry:
            s.expire_all()
            return build(s, punch_id, settings, _retry=False)
    return out


def download_name(p: Punch) -> str:
    local = p.server_received_at.astimezone(LOCAL_TZ)
    return f"timbro_{local:%Y-%m-%d}_{'entrata' if p.kind == 'in' else 'uscita'}_{p.id}.zip"


def _stamp_base(st: Stamp) -> str:
    return f"stamps/{st.target}_{st.provider}"


def _chain_to_day_head(s: Session, p: Punch) -> tuple[list[dict], list[Stamp]]:
    """Anelli dalla timbratura fino all'ultimo record del suo giorno, e le marche di quel giorno."""
    day = p.server_received_at.astimezone(LOCAL_TZ).date()
    end = datetime.combine(day + timedelta(days=1), time.min, LOCAL_TZ)
    rows = s.scalars(select(Punch).where(Punch.id >= p.id, Punch.server_received_at < end).order_by(Punch.id)).all()
    keys = {}
    links = []
    for r in rows:
        if r.device_id not in keys:
            keys[r.device_id] = public_key_der(s.get(Device, r.device_id).public_key_pem)
        links.append(
            {
                "id": r.id,
                "prev_chain_hash": r.prev_chain_hash,
                "sha256_manifest": _sha(r.manifest_raw),
                "sha256_signature": _sha(r.device_signature),
                "sha256_device_key_der": _sha(keys[r.device_id]),
                "server_received_at": r.server_received_at.isoformat(),
                "chain_hash": r.chain_hash,
            }
        )
    day_stamps = s.scalars(select(Stamp).where(Stamp.target == "chain", Stamp.chain_day == day)).all()
    return links, list(day_stamps)


CHAIN_SPEC = (
    "chain_hash = SHA-256( prev_chain_hash come 32 byte (decodifica dell'esadecimale; il primo record "
    "ha 32 byte a zero) || SHA-256(byte di manifest.json) come 32 byte || SHA-256(byte di manifest.sig) "
    "come 32 byte || SHA-256(chiave pubblica del telefono in DER SubjectPublicKeyInfo) come 32 byte || "
    "server_received_at esattamente come scritto in record.json, in UTF-8 ). In record.json gli "
    "SHA-256 intermedi sono riportati in esadecimale: vanno riconvertiti in byte prima della concatenazione."
)


def _time_check(p: Punch) -> dict:
    tc = services.time_check(p)
    return {
        "ora": tc.time.isoformat(),
        "confermata": tc.confirmed_by,
        "avviso": tc.warning,
        "nota": (
            "L'ora del telefono vale se la conferma la ricezione immediata o un orologio che l'utente non può spostare "
            "(rete, satelliti), letto dall'app e scritto nel manifest firmato (campo clocks); verifica.py la ricontrolla."
        ),
    }


def _provenance(p: Punch, settings: Settings) -> dict:
    """Da dove vengono il codice dell'app che ha timbrato, del server che ha ricevuto la timbratura e di questo
    pacchetto (verifica.py): release pubbliche con sorgenti marcati e attestazione di GitHub (docs/PROGETTO.md §25)."""
    base = settings.public_sources_url
    app_commit, local_build = versione.app_source(json.loads(p.manifest_raw).get("app_version"))
    attested = json.loads(p.attestation_json).get("packages") if p.attestation_json else None
    now = versione.server_commit()
    return {
        "sorgenti_pubblici": base,
        "app": {
            "versione": json.loads(p.manifest_raw).get("app_version"),
            "versione_attestata": attested,
            "commit": app_commit,
            "release": versione.release_url(base, app_commit) if app_commit and not local_build else None,
            "sorgenti": versione.tree_url(base, app_commit, "android") if app_commit and not local_build else None,
            "nota": (
                "compilata in locale con modifiche non registrate: nessuna release pubblica" if local_build else
                "app costruita dal flusso di rilascio del repository pubblico, dai sorgenti di quella release, e "
                "firmata da Google Play; le impronte del suo codice sono nella release (impronte-app.txt)"
            ),
        },
        "server_alla_ricezione": {
            "commit": p.server_version,
            "release": versione.release_url(base, p.server_version),
            "sorgenti": versione.tree_url(base, p.server_version, "timbro"),
        },
        "server_del_pacchetto": {
            "commit": now,
            "release": versione.release_url(base, now),
            "verifica_py": versione.file_url(base, now, "timbro/verifica_pacchetto.py") if now else None,
        },
        "nota": (
            "Ogni versione in produzione è pubblicata prima del deploy, con il tag timbro-<7 cifre del commit>: "
            "archivio dei sorgenti con marche qualificate (ACCV, Sectigo) e OpenTimestamps, attestazione di "
            "provenienza di GitHub (gh attestation verify). verifica.py è identico al file del server del pacchetto."
        ),
    }


def _first_pem(text: str) -> str:
    end = "-----END CERTIFICATE-----"
    return text[: text.index(end) + len(end)] + "\n"


def _revocation(s: Session, p: Punch) -> dict | None:
    """Stato della lista di revoca di Google per la catena di questa timbratura, all'ultimo controllo riuscito."""
    if not p.attestation_pem:
        return None
    rev = services.get_json_setting(s, services.REVOCATION_KEY) or {}
    return {
        "controllata_il": rev.get("last_success"),
        "revocati": [h for h in rev.get("revoked", []) if h.get("punch_id") == p.id],
    }


def _record(
    p: Punch, device: Device, links: list[dict], stamps: list[Stamp], wifi: dict | None = None,
    revocation: dict | None = None, settings: Settings | None = None,
) -> dict:
    return {
        "formato": FORMAT,
        "timbratura": {
            "id": p.id,
            "tipo": p.kind,
            "sha256_video": p.sha256,
            "dimensione_video": p.size,
            "ora_dispositivo": p.device_time,
            "ricevuta_dal_server": p.server_received_at.isoformat(),
            "posizione": json.loads(p.location_json) if p.location_json else None,
            "ora_timbratura": _time_check(p),
            "chain_hash": p.chain_hash,
            "video_cancellato": p.video_deleted_at.isoformat() if p.video_deleted_at else None,
        },
        "dispositivo": {
            "id": device.id,
            "nome": device.name,
            "nota": (
                "device_pubkey.pem è la chiave che firma manifest.sig. device_attestation.pem, se presente, è la catena "
                "che il telefono ha mandato all'associazione: conservata così com'è, non verificata dal server. "
                "L'attestazione verificata è quella della timbratura, in attestazione/."
            ),
        },
        "attestazione": (
            {
                "esito": json.loads(p.attestation_json) if p.attestation_json else None,
                "sha256_catena_pem": _sha(p.attestation_pem.encode()) if p.attestation_pem else None,
                # dopo la timbratura Google può revocare chiavi di attestazione trapelate: allora la catena non
                # prova più niente, anche se all'arrivo era valida
                "lista_di_revoca_google": revocation,
                # l'app che il server si aspetta: verifica.py la confronta con quella scritta nell'attestazione
                "app_attesa": {
                    "pacchetto": settings.attestation_package if settings else None,
                    "impronte_certificato_firma": list(settings.attestation_signing_cert_sha256) if settings else [],
                    "nota": (
                        "Certificato con cui Google Play firma l'app (Play App Signing, chiave custodita da Google). "
                        "Si ritrova scaricando l'app da Google Play (apksigner verify --print-certs)."
                    ),
                },
                "nota": (
                    "Catena di Android Key Attestation di una chiave usa e getta generata dal telefono alla timbratura, "
                    "con challenge = SHA-256 di manifest.json: dice se la chiave stava nell'hardware sicuro, se il "
                    "bootloader era bloccato e l'avvio verificato, e quale app l'ha chiesta. File in attestazione/, "
                    "verifica in VERIFICA.md §8."
                ),
            }
            if p.attestation_json
            else None
        ),
        "reti_wifi": (
            {
                **wifi,
                "nota": (
                    "Reti dichiarate dal telefono nel manifest firmato (campo wifi). I conteggi \"viste in altre "
                    "timbrature\" li calcola il server sull'archivio al momento dell'esportazione."
                ),
            }
            if wifi
            else None
        ),
        "provenienza": _provenance(p, settings) if settings else None,
        "formula_catena": CHAIN_SPEC,
        "catena_fino_alla_testa_del_giorno": links,
        "nota_catena": (
            "Gli anelli successivi alla timbratura (stesso giorno) servono solo a collegarla alla marca "
            "giornaliera: di quei record sono riportati esclusivamente impronte e ora di ricezione, "
            "nessun contenuto."
        ),
        "marche": [
            {
                "file": _stamp_base(st),
                "oggetto": st.target,
                "giorno": st.chain_day.isoformat() if st.chain_day else None,
                "fornitore": st.provider,
                "tipo": st.kind,
                "qualificata": st.qualified,
                "stato": st.status,
                "impronta_marcata": st.digest,
                "gen_time": st.gen_time.isoformat() if st.gen_time else None,
                "dettagli": json.loads(st.info_json) if st.info_json else None,
            }
            for st in stamps
        ],
    }


def _instructions(
    p: Punch, stamps: list[Stamp], has_video: bool, has_attestation: bool = False, provenance: dict | None = None
) -> str:
    def rfc(target):
        return [st for st in stamps if st.target == target and st.kind == "rfc3161" and st.status == "complete"]

    def ots(target):
        return [st for st in stamps if st.target == target and st.kind == "ots"]

    local = p.server_received_at.astimezone(LOCAL_TZ)
    lines = [
        f"# Verifica della timbratura n. {p.id} ({'entrata' if p.kind == 'in' else 'uscita'})",
        "",
        f"Ricevuta dal server il {local:%d/%m/%Y alle %H:%M:%S} (ora italiana), {p.server_received_at.isoformat()} UTC.",
        "",
        "Strumenti: Python 3.8 o successivo (solo libreria standard). In alternativa, per ripetere i controlli con",
        "strumenti diffusi: `openssl` (1.1 o successivo) e il client OpenTimestamps (`pip install opentimestamps-client`).",
        "Tutti i comandi si eseguono in questa cartella.",
        "",
        "## 0. Verifica completa automatica",
        "",
        "```sh",
        "python3 verifica.py             # radici di fiducia dalle fonti ufficiali in rete",
        "python3 verifica.py --offline   # senza rete: radici del pacchetto, impronte da confrontare",
        "```",
        "",
        "Non usa librerie esterne né il server di Timbro: la crittografia (RSA, ECDSA, hash) è scritta nello script,",
        "leggibile. Le radici di fiducia le prende dalle fonti ufficiali, con gli indirizzi scritti in `FONTI` nello",
        "script: radici e lista di revoca di Google per l'attestazione, Trusted List europea (ec.europa.eu) e lista",
        "nazionale che questa indica per i prestatori qualificati, siti dei prestatori non qualificati, due explorer",
        "Bitcoin. Controlla:"
        "",
        "- impronte del video, del manifest, della firma e della chiave, e ogni anello della catena fino alla testa",
        "  marcata del giorno;",
        "- la firma del telefono su `manifest.json`;",
        "- l'attestazione hardware: challenge, catena firmata fino alla radice di Google, stato del telefono, app,",
        "  versione e certificato di firma dell'app;",
        "- ogni marca RFC 3161: impronta, firma, catena fino alla radice del prestatore, validità all'ora della marca,",
        "  qualifica eIDAS dichiarata, risposte OCSP e CRL conservate, servizio qualificato nella Trusted List;",
        "- ogni prova OpenTimestamps: percorso fino alla radice di Merkle del blocco Bitcoin e proof-of-work",
        "  dell'intestazione del blocco.",
        "",
        "Con `--offline` usa le copie nel pacchetto (radici, Trusted List) e ne stampa le impronte SHA-256, da",
        "confrontare con le fonti ufficiali. I passi seguenti ripetono gli stessi controlli con `openssl` e `ots`.",
        "",
        "`verifica.py` arriva dal server insieme al pacchetto. In caso di dubbi si usa lo stesso file pubblicato su",
        "GitHub per la versione del server del pacchetto (comando nel §9), lanciato su questa cartella: controlla anche",
        "che il `verifica.py` qui accanto sia identico a quello pubblicato.",
        "",
        "## 1. Impronta del video",
        "",
        "```sh",
        "openssl dgst -sha256 video.mp4" if has_video else "# video non più conservato: resta l'impronta registrata e marcata",
        f"# atteso: {p.sha256}",
        "```",
        "",
        "## 2. Firma del telefono sul manifest",
        "",
        "Il manifest contiene l'impronta del video, entrata o uscita, l'ora del telefono e la posizione.",
        "",
        "```sh",
        "openssl dgst -sha256 -verify device_pubkey.pem -signature manifest.sig manifest.json",
        "```",
        "",
        "## 3. Marche temporali sul video",
        "",
        "```sh",
    ]
    for st in rfc("video"):
        src = "-data video.mp4" if has_video else f"-digest {p.sha256}"
        q = "   # qualificata eIDAS" if st.qualified else ""
        lines.append(f"openssl ts -verify {src} -in stamps/video_{st.provider}.tsr -CAfile stamps/{st.provider}_trust.pem{q}")
        lines.append(f"openssl ts -reply -in stamps/video_{st.provider}.tsr -text | grep 'Time stamp'")
    lines += [
        "```",
        "",
        "## 4. Marche sul record della timbratura",
        "",
        f"Coprono `chain_hash` = `{p.chain_hash}`, che include manifest, firma, chiave del telefono e ora di",
        "ricezione (formula in `record.json` → `formula_catena`).",
        "",
        "```sh",
    ]
    for st in rfc("record"):
        q = "   # qualificata eIDAS" if st.qualified else ""
        lines.append(f"openssl ts -verify -digest {st.digest} -in stamps/record_{st.provider}.tsr -CAfile stamps/{st.provider}_trust.pem{q}")
    lines += [
        "```",
        "",
        "## 5. Marca giornaliera della catena",
        "",
        "`verifica.py` (passo 0) ricalcola gli anelli dalla timbratura all'ultimo record del giorno: il risultato",
        "è l'impronta marcata qui sotto. Così la timbratura, invariata, risulta esistente entro la fine del giorno",
        "anche tramite la marca qualificata della catena.",
        "",
        "```sh",
    ]
    day = rfc("chain")
    for st in day:
        q = "   # qualificata eIDAS" if st.qualified else ""
        lines.append(f"openssl ts -verify -digest {st.digest} -in stamps/chain_{st.provider}.tsr -CAfile stamps/{st.provider}_trust.pem{q}")
    if not day:
        lines.append("# marca giornaliera non ancora disponibile al momento dell'esportazione")
    lines += [
        "```",
        "",
        "## 6. OpenTimestamps (ancoraggio alla blockchain Bitcoin)",
        "",
        "Se una prova risulta ancora in attesa, `ots upgrade <file>.ots` la completa scaricando le parti mancanti.",
        "",
        "```sh",
    ]
    for st in ots("video"):
        lines.append("ots verify -f video.mp4 stamps/video_ots.ots" if has_video else f"ots verify -d {p.sha256} stamps/video_ots.ots")
    for st in ots("record"):
        lines.append(f"ots verify -d {st.digest} stamps/record_ots.ots")
    for st in ots("chain"):
        lines.append(f"ots verify -d {st.digest} stamps/chain_ots.ots")
    lines += [
        "```",
        "",
        "## 7. Qualifica eIDAS e non revoca",
        "",
        "I token qualificati contengono l'estensione ETSI EN 319 422 `esi4-qtstStatement-1` (OID 0.4.0.19422.1.1);",
        "il certificato del servizio è elencato come QTST *granted* nella Trusted List nazionale (copia in",
        "`trusted-list/`, verificabile anche su https://eidas.ec.europa.eu/efda/tl-browser/). Le risposte OCSP",
        "conservate (`stamps/*_ocsp*.der`, leggibili con `openssl ocsp -respin <file> -resp_text -noverify`)",
        "attestano che il certificato della TSA non era revocato al momento della marca.",
        "",
    ]
    if has_attestation:
        lines += [
            "## 8. Attestazione hardware del telefono",
            "",
            "Alla timbratura il telefono ha generato nel chip sicuro una chiave usa e getta con challenge = SHA-256",
            "di `manifest.json`; Android ne ha rilasciato la catena di certificati, che risale alle radici di Google.",
            "`verifica.py` (passo 0) controlla la challenge e mostra cosa attesta (hardware, bootloader, avvio, app).",
            "",
            "```sh",
            "# catena fino alle radici di Google (date non controllate: l'esistenza nel tempo la provano le marche qui sotto)",
            "openssl verify -no_check_time -CAfile attestazione/radici_google.pem -untrusted attestazione/catena.pem attestazione/foglia.pem",
            "# su alcuni telefoni StrongBox l'emittente scritto nella foglia non coincide con il nome dell'intermedio:",
            "# openssl risponde \"unable to get local issuer certificate\" anche se la firma è valida (il server",
            "# collega i certificati per firma, non per nome: record.json -> attestazione.esito)",
        ]
        for st in rfc("attest"):
            q = "   # qualificata eIDAS" if st.qualified else ""
            lines.append(f"openssl ts -verify -data attestazione/catena.pem -in stamps/attest_{st.provider}.tsr -CAfile stamps/{st.provider}_trust.pem{q}")
        for st in ots("attest"):
            lines.append("ots verify -f attestazione/catena.pem stamps/attest_ots.ots")
        lines += [
            "```",
            "",
            "Le radici sono quelle pubblicate da Google: https://developer.android.com/privacy-and-security/security-key-attestation",
            "Dopo la timbratura Google può revocare chiavi di attestazione trapelate: `record.json` →",
            "`attestazione.lista_di_revoca_google` dice cosa risultava all'esportazione; per lo stato di oggi si",
            "cercano i seriali (`attestazione.esito.serials`) in https://android.googleapis.com/attestation/status.",
            "",
        ]
    if provenance:
        app, srv, pkg = provenance["app"], provenance["server_alla_ricezione"], provenance["server_del_pacchetto"]
        lines += [
            "## 9. Provenienza del codice",
            "",
            f"Sorgenti pubblici dell'app e del server: {provenance['sorgenti_pubblici']}. Ogni versione in produzione vi",
            "è pubblicata prima del deploy, con una release immutabile: archivio dei sorgenti con marche qualificate",
            "(ACCV, Sectigo) e OpenTimestamps, attestazione di provenienza di GitHub, e per l'app le impronte del codice.",
            "",
            f"- app che ha timbrato: `{app['versione']}`" + (f", release {app['release']}" if app["release"] else f" ({app['nota']})"),
            "- server che ha ricevuto la timbratura: " + (f"`{srv['commit']}`, release {srv['release']}" if srv["commit"] else "non registrato"),
            "- server che ha prodotto questo pacchetto: " + (f"`{pkg['commit']}`, release {pkg['release']}" if pkg["commit"] else "non registrato"),
            "",
            "La versione del server è **dichiarata, non dimostrata**: il server non ha un'attestazione hardware come il",
            "telefono, e nessuno può provare quale codice girasse davvero. Nessuna prova dipende da essa: firme, marche,",
            "catena e attestazione si verificano contro Google, i prestatori qualificati e Bitcoin, sul computer di chi",
            "controlla. L'app invece è attestata: l'attestazione hardware riporta versione e firma di Google Play.",
            "",
            "```sh",
            *((
                "# in caso di dubbi su verifica.py: lo script pubblicato, al posto di quello del pacchetto",
                f"curl -sL {pkg['verifica_py']} -o verifica-github.py",
                "python3 verifica-github.py .",
                "# oppure il solo confronto delle impronte",
                f"curl -sL {pkg['verifica_py']} | sha256sum; sha256sum verifica.py",
            ) if pkg["verifica_py"] else ("# versione del server non registrata: script pubblicato non individuabile",)),
            "# provenienza di un archivio scaricato da una release",
            "gh attestation verify timbro-<commit>-sorgenti.tar.gz --repo " + provenance["sorgenti_pubblici"].removeprefix("https://github.com/"),
            "```",
            "",
            "In rete `verifica.py` fa anche da sé il confronto con lo script pubblicato, ma un controllo fatto dallo script",
            "vale quanto lo script: per questo, in caso di dubbi, si usa quello scaricato da GitHub.",
            "",
        ]
    return "\n".join(lines)


# verifica completa con la sola libreria standard di Python (firme, catene, marche, OCSP/CRL, Trusted List, Bitcoin):
# un modulo vero, provato dai test, copiato nel pacchetto come verifica.py
VERIFY_SCRIPT = (Path(__file__).parent / "verifica_pacchetto.py").read_text(encoding="utf-8")


def _latest_tl(settings: Settings) -> Path | None:
    d = settings.data_dir / "trusted-lists"
    files = sorted(d.glob("*_ES_*.xml")) if d.exists() else []
    return files[-1] if files else None


def _pem(der: bytes) -> str:
    b64 = base64.b64encode(der).decode()
    body = "\n".join(b64[i : i + 64] for i in range(0, len(b64), 64))
    return f"-----BEGIN CERTIFICATE-----\n{body}\n-----END CERTIFICATE-----\n"


def _sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()

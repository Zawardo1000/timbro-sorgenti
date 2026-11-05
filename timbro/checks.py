"""Controlli periodici (worker) e a comando (CLI): Trusted List e integrità dell'archivio.

L'esito si salva nella tabella settings, da dove lo legge problems.py per gli avvisi su Telegram.
"""

import json
from datetime import date

import httpx
from sqlalchemy import select

from timbro import attestation, services
from timbro.config import Settings
from timbro.db import Database, utcnow
from timbro.models import Punch, Stamp
from timbro.stamping.trusted_list import check_all
from timbro.verify import verify_archive

TL_KEY = "check_trusted_list"
ARCHIVE_KEY = "check_archive"
REVOCATION_KEY = services.REVOCATION_KEY


def trusted_list(db: Database, settings: Settings) -> dict:
    """Controlla i provider qualificati sulla EU Trusted List e ne archivia una copia (data_dir/trusted-lists).

    Per ogni provider conta l'identità TL usata dall'ultima marca completata (se nota), altrimenti basta
    che una delle identità fissate sia granted. Le altre identità sono solo informative.
    """
    with httpx.Client(headers={"User-Agent": "timbro"}, follow_redirects=True, timeout=60) as client:
        results = check_all(client, archive_dir=settings.data_dir / "trusted-lists")

    in_use: dict[str, str] = {}
    with db.session() as s:
        for provider in {r.provider for r in results}:
            last = s.scalar(
                select(Stamp.info_json)
                .where(Stamp.provider == provider, Stamp.status == "complete")
                .order_by(Stamp.completed_at.desc())
                .limit(1)
            )
            if last and (ident := json.loads(last).get("tl_identity")):
                in_use[provider] = ident

    lines: list[str] = []
    bad: list[str] = []
    for provider in dict.fromkeys(r.provider for r in results):
        rows = [r for r in results if r.provider == provider]
        used = in_use.get(provider)
        for r in rows:
            mark = " <- in uso" if r.identity == used else ""
            state = f"{r.status} dal {r.status_since}" if r.found else "NON TROVATO"
            lines.append(f"{provider:10} {r.identity[:16]}... {state}{mark}")
            if r.found:
                lines.append(f"{'':10} {r.service_name}")
        good = any(r.identity == used and r.granted for r in rows) if used else any(r.granted for r in rows)
        if not good:
            lines.append(f"{provider:10} ATTENZIONE: nessuna identità valida per questo provider")
            bad.append(provider)
    if results:
        tl = results[0]
        lines.append(f"TL {tl.territory} n.{tl.tl_sequence} emessa {tl.tl_issued}")
    return {"ok": not bad, "not_granted": bad, "lines": lines}


def run_trusted_list(db: Database, settings: Settings) -> dict:
    """Come trusted_list(), ma salva l'esito (anche un errore di rete) per gli avvisi."""
    now = utcnow().isoformat()
    with db.session() as s:
        previous = services.get_json_setting(s, TL_KEY) or {}
    try:
        record = {**trusted_list(db, settings), "at": now, "last_success": now, "error": None}
    except Exception as e:  # rete, formato della lista: si riprova al giro dopo
        record = {**previous, "at": now, "error": f"{type(e).__name__}: {e}"[:500]}
    with db.session() as s:
        services.set_json_setting(s, TL_KEY, record)
    return record


def run_attestation_revocations(db: Database, settings: Settings) -> dict:
    """Confronta i certificati di tutte le attestazioni conservate con la lista di revoca di Google (chiavi di
    attestazione compromesse: un'attestazione firmata con quelle non prova più niente). Salva l'esito e i seriali
    revocati, che servono anche a verificare le timbrature in arrivo. Dopo un errore restano quelli dell'ultima lista
    scaricata."""
    now = utcnow().isoformat()
    with db.session() as s:
        previous = services.get_json_setting(s, REVOCATION_KEY) or {}
        serials = {
            pid: json.loads(raw).get("serials", [])
            for pid, raw in s.execute(select(Punch.id, Punch.attestation_json).where(Punch.attestation_json.is_not(None)))
        }
    try:
        # si scarica anche senza attestazioni: la lista serve pure alle timbrature in arrivo (services.check_attestation)
        with httpx.Client(headers={"User-Agent": "timbro"}, timeout=60) as client:
            r = client.get(settings.attestation_status_url)
            r.raise_for_status()
            status = r.json()
        revoked = attestation.revoked_serials(status)
        hits = attestation.check_revocations(serials, status)
        record = {
            "at": now, "last_success": now, "revoked": hits, "checked": len(serials), "error": None,
            "revoked_serials": sorted(revoked),
        }
    except Exception as e:
        record = {**previous, "at": now, "error": f"{type(e).__name__}: {e}"[:500]}
    with db.session() as s:
        services.set_json_setting(s, REVOCATION_KEY, record)
    return record


def run_archive(db: Database, settings: Settings, today: date | None = None) -> dict:
    """Verifica completa dell'archivio (catena, impronte, marche della catena, token RFC 3161, file video) e ne salva
    l'esito, anche quando la verifica stessa non riesce (error).

    Prima di partire segna "started_at": se il processo muore a metà (memoria), il worker non la ripete a raffica
    lo stesso giorno e problems.py lo segnala. La verifica legge in una transazione REPEATABLE READ di sola lettura:
    vede un solo istante anche se nel frattempo arrivano timbrature.
    """
    with db.session() as s:
        previous = services.get_json_setting(s, ARCHIVE_KEY) or {}
        started = utcnow().isoformat()
        services.set_json_setting(s, ARCHIVE_KEY, {**previous, "started_at": started})
    report = None
    try:
        with db.session() as s:
            s.connection(execution_options={"isolation_level": "REPEATABLE READ", "postgresql_readonly": True})
            report = verify_archive(s, today, videos_dir=settings.videos_dir)
            s.rollback()
        if report.missing_videos:
            # fuori dalla fotografia: un video cancellato nel frattempo non è un'alterazione
            with db.session() as s:
                kept = set(
                    s.scalars(
                        select(Punch.id).where(Punch.id.in_(report.missing_videos), Punch.video_deleted_at.is_(None))
                    )
                )
            report.errors.extend(report.missing_video_errors(kept))
            report.missing_videos = []
        record = {
            "at": utcnow().isoformat(),
            "started_at": started,
            "last_success": utcnow().isoformat(),
            "ok": report.ok,
            "links": report.links,
            "stamps_checked": report.stamps_checked,
            "errors": report.errors[:20],
            "warnings": report.warnings[:20],
            "error": None,
        }
    except BaseException as e:
        # anche Ctrl+C sul comando verify-chain: la verifica risulta conclusa con errore (si riprova fra un'ora),
        # non "iniziata e mai finita"
        record = {**previous, "at": utcnow().isoformat(), "started_at": started, "error": f"{type(e).__name__}: {e}"[:500]}
        with db.session() as s:
            services.set_json_setting(s, ARCHIVE_KEY, record)
        if not isinstance(e, Exception):
            raise
        return {**record, "report": report}
    with db.session() as s:
        services.set_json_setting(s, ARCHIVE_KEY, record)
    return {**record, "report": report}

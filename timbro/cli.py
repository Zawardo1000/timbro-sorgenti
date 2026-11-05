"""Comandi di amministrazione: python -m timbro <comando>.

Nel container: docker compose exec timbro python -m timbro <comando> (sul PC e, nella cartella dello stack, sul
server)

Solo "migrate" cambia lo schema (le migrazioni le applica l'app all'avvio): gli altri comandi si fermano se lo
schema è indietro, e funzionano se è più nuovo del codice (ritorno a una versione precedente).
"""

import argparse
import sys

from sqlalchemy import select, update

from timbro import checks, services
from timbro.config import get_settings
from timbro.db import Database, utcnow
from timbro.models import Device, Stamp, User


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m timbro")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("migrate", help="crea o aggiorna lo schema del database (Alembic)")
    p = sub.add_parser("create-user", help="crea un utente")
    p.add_argument("username")
    p = sub.add_parser("pairing-code", help="codice monouso per associare un telefono")
    p.add_argument("username")
    p = sub.add_parser("set-role", help="ruolo di un utente: user (solo le sue timbrature) o admin (utenti e orari)")
    p.add_argument("username")
    p.add_argument("role", choices=["user", "admin"])
    sub.add_parser("verify-chain", help="verifica catena, marche e coerenza dell'archivio (offline)")
    sub.add_parser("check-tl", help="controlla i provider qualificati sulla EU Trusted List")
    sub.add_parser("list-devices", help="elenca i telefoni associati")
    p = sub.add_parser("revoke-device", help="revoca un telefono (perso o sostituito) e i link che ha creato")
    p.add_argument("device_id")
    p.add_argument("--keep-shares", action="store_true", help="lascia attivi i link creati da quel telefono")
    p = sub.add_parser("retry-stamps", help="rimette in coda le marche RFC 3161 segnate come fallite")
    p.add_argument("--provider", help="solo questo provider (es. accv)")
    args = ap.parse_args(argv)

    settings = get_settings()
    db = Database(settings.database_url)
    try:
        if args.cmd == "migrate":
            state = db.migrate()
            print("schema aggiornato" if state == "aggiornato" else "schema più nuovo del codice: nessuna migrazione")
            return 0
        state = db.schema_state()
        if state == "indietro":
            print("schema del database non aggiornato: avvia l'app o lancia python -m timbro migrate", file=sys.stderr)
            return 1
        if state == "piu-nuovo":
            print("avviso: schema del database più nuovo del codice (ritorno a una versione precedente?)", file=sys.stderr)
        if args.cmd == "check-tl":
            return check_tl(db, settings)
        if args.cmd == "verify-chain":
            return verify_chain(db, settings)
        with db.session() as s:
            return run(args, s, settings)
    finally:
        db.dispose()


def run(args, s, settings) -> int:
    if args.cmd == "create-user":
        u = services.create_user(s, args.username)
        print(f"utente {u.username} creato (id {u.id})")
    elif args.cmd == "pairing-code":
        u = s.scalar(select(User).where(User.username == args.username))
        if u is None:
            print("utente inesistente", file=sys.stderr)
            return 1
        code = services.new_pairing_code(s, u, settings)
        print(f"codice: {code}  (valido {settings.pairing_code_minutes} minuti, monouso)")
    elif args.cmd == "set-role":
        from timbro import accounts

        u = s.scalar(select(User).where(User.username == args.username))
        if u is None:
            print("utente inesistente", file=sys.stderr)
            return 1
        try:
            accounts.set_role(s, u.id, args.role)
        except ValueError as e:
            print(e, file=sys.stderr)
            return 1
        print(f"utente {u.username}: ruolo {args.role}")
    elif args.cmd == "list-devices":
        for d in s.scalars(select(Device).order_by(Device.created_at)):
            state = f"revocato {d.revoked_at:%Y-%m-%d}" if d.revoked_at else "attivo"
            print(f"{d.id}  {d.name:20} associato {d.created_at:%Y-%m-%d}  {state}")
    elif args.cmd == "revoke-device":
        try:
            d, n = services.revoke_device(s, args.device_id, keep_shares=args.keep_shares)
        except ValueError as e:
            print(e, file=sys.stderr)
            return 1
        print(f"revocato {d.id} ({d.name}): token e chiavi non sono più accettati; link revocati: {n}")
    elif args.cmd == "retry-stamps":
        q = update(Stamp).where(Stamp.status == "failed", Stamp.kind == "rfc3161")
        if args.provider:
            q = q.where(Stamp.provider == args.provider)
        n = s.execute(q.values(status="pending", attempts=0, next_attempt_at=utcnow(), last_error=None)).rowcount
        s.commit()
        print(f"marche rimesse in coda: {n} (il worker le riprova entro pochi secondi)")
    return 0


def verify_chain(db: Database, settings) -> int:
    record = checks.run_archive(db, settings)
    report = record["report"]
    if report is None:
        print(f"verifica non riuscita: {record['error']}", file=sys.stderr)
        return 4
    for w in report.warnings:
        print(f"avviso: {w}")
    for e in report.errors:
        print(f"ERRORE: {e}", file=sys.stderr)
    print(f"catena: {report.links} anelli, marche RFC 3161 riverificate: {report.stamps_checked}")
    if not report.ok:
        print("ARCHIVIO NON INTEGRO", file=sys.stderr)
        return 2
    print("archivio integro")
    return 0


def check_tl(db: Database, settings) -> int:
    record = checks.run_trusted_list(db, settings)
    if record.get("error"):
        print(f"controllo non riuscito: {record['error']}", file=sys.stderr)
        return 4
    for line in record["lines"]:
        print(line)
    ok = record["ok"]
    print("OK: i provider qualificati risultano 'granted'" if ok else "ATTENZIONE: qualche provider non è più qualificato")
    return 0 if ok else 3


if __name__ == "__main__":
    sys.exit(main())

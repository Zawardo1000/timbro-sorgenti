"""Lavori in background: marche RFC 3161, prove OpenTimestamps, marca giornaliera della catena, conservazione e
controlli periodici.

Deve girare in un solo processo (lock d'istanza in db.py): la selezione delle marche da fare non è coordinata fra
processi diversi.
"""

import json
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import httpx
from sqlalchemy import func, select, update

from timbro import checks, evidence, mailer, services
from timbro.config import Settings
from timbro.db import Database, utcnow
from timbro.models import ContestNotice, Punch, Stamp
from timbro.stamping import ots, tsa

log = logging.getLogger("timbro.worker")
LOCAL_TZ = ZoneInfo("Europe/Rome")


def backoff(attempts: int, age: timedelta = timedelta(0)) -> timedelta:
    """Attesa prima del prossimo tentativo: raddoppia fino a un'ora; dopo due giorni di errori, ogni 6 ore.

    Un errore temporaneo (rete, TSA ferma, orologio sfasato) non rende mai definitiva una marca: si riprova finché
    riesce. Solo i rifiuti permanenti della TSA la segnano "failed".
    """
    if age >= timedelta(days=2):
        return timedelta(hours=6)
    return timedelta(seconds=min(10 * 2 ** max(attempts - 1, 0), 3600))


@dataclass(frozen=True)
class _Job:
    """Quello che serve per lavorare una marca, letto in una sessione breve: durante le chiamate di rete nessuna
    transazione resta aperta."""

    id: int
    kind: str
    status: str
    provider: str
    digest: str
    token: bytes | None
    info_json: str | None
    attempts: int
    created_at: datetime


class Worker:
    def __init__(self, db: Database, settings: Settings):
        self.db = db
        self.settings = settings
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        # pool separati: le TSA (secondi) non aspettano mai invii e upgrade OTS (anche minuti)
        self._tsa_pool = ThreadPoolExecutor(max_workers=6, thread_name_prefix="tsa")
        self._ots_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="ots")
        # controlli lunghi (Trusted List, verifica dell'archivio): uno alla volta, senza fermare il ciclo
        self._check_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="check")
        # mail di contestazione: una alla volta, il server SMTP può metterci decine di secondi
        self._mail_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mail")
        self._mail_in_flight = False
        self._checks_in_flight: set[str] = set()
        self._in_flight: set[int] = set()
        self._last_retention: datetime | None = None
        self._last_chain: tuple[datetime, date] | None = None
        self._last_lock_check: datetime | None = None
        self._lock = threading.Lock()
        # fine dell'ultimo giro riuscito: se è troppo vecchia, problems.py segnala il worker fermo
        self.last_loop_at: datetime | None = None
        # fasi del ciclo che falliscono: nome -> (da quando, ultimo errore); problems.py le segnala
        self.failing: dict[str, tuple[datetime, str]] = {}
        self.instance_lock_ok = True

    # --- ciclo di vita -------------------------------------------------------------------------
    def start(self) -> None:
        self.last_loop_at = utcnow()  # appena avviato conta come un giro: il primo arriva fra pochi secondi
        self._thread = threading.Thread(target=self._loop, name="timbro-worker", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread:
            self._thread.join(timeout=timeout)
        for pool in (self._tsa_pool, self._ots_pool, self._check_pool, self._mail_pool):
            pool.shutdown(wait=False, cancel_futures=True)

    def wake(self) -> None:
        self._wake.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            # clear prima del lavoro: un wake() durante run_once non va perso
            self._wake.clear()
            try:
                self.run_once()
                self.last_loop_at = utcnow()
            except Exception:
                log.exception("errore nel ciclo del worker")
            self._wake.wait(self.settings.worker_interval_s)

    # --- lavoro -------------------------------------------------------------------------------
    def _phase(self, name: str, fn) -> None:
        """Una fase che fallisce non ferma le altre (né le marche): l'errore resta in self.failing."""
        try:
            fn()
        except Exception as e:
            log.exception("worker, fase %s", name)
            since = self.failing.get(name, (utcnow(), ""))[0]
            self.failing[name] = (since, f"{type(e).__name__}: {e}"[:300])
        else:
            self.failing.pop(name, None)

    def run_once(self, now: datetime | None = None, wait: bool = False) -> None:
        """Accoda le marche scadute senza aspettarne l'esito (wait=True solo per i test)."""
        now = now or utcnow()
        self._phase("catena", lambda: self._maybe_schedule_chain(now))
        self._phase("conservazione", lambda: self.retention(now))
        if self.settings.periodic_checks_enabled:
            self._phase("controlli", lambda: self.periodic_checks(now))
        self._phase("lock", lambda: self._check_instance_lock(now))
        if mailer.configured(self.settings):
            self._phase("mail", lambda: self._schedule_mails(now, wait))
        with self.db.session() as s:
            due = s.execute(
                select(Stamp.id, Stamp.kind)
                .where(Stamp.status.in_(("pending", "upgrading")), Stamp.next_attempt_at <= now)
                .order_by(Stamp.qualified.desc(), Stamp.id)
            ).all()
        futures = []
        for stamp_id, kind in due:
            with self._lock:
                if stamp_id in self._in_flight:
                    continue
                self._in_flight.add(stamp_id)
            pool = self._tsa_pool if kind == "rfc3161" else self._ots_pool
            futures.append(pool.submit(self._run_stamp, stamp_id, now))
        if wait:
            for f in futures:
                f.result()

    def _run_stamp(self, stamp_id: int, due_by: datetime | None = None) -> None:
        try:
            self.process_stamp(stamp_id, due_by)
        except Exception:
            log.exception("marca %s", stamp_id)
        finally:
            with self._lock:
                self._in_flight.discard(stamp_id)

    def process_stamp(self, stamp_id: int, due_by: datetime | None = None) -> None:
        # 1. lettura in una sessione breve
        with self.db.session() as s:
            st = s.get(Stamp, stamp_id)
            if st is None or st.status not in ("pending", "upgrading"):
                return
            if due_by is not None and st.next_attempt_at > due_by:
                return  # riprogrammata da un'altra esecuzione mentre questa era in coda: si rispetta l'attesa
            job = _Job(st.id, st.kind, st.status, st.provider, st.digest, st.token, st.info_json, st.attempts, st.created_at)
        # 2. rete, senza transazioni aperte
        try:
            with httpx.Client(headers={"User-Agent": "timbro"}) as client:
                if job.kind == "rfc3161":
                    values = self._do_rfc3161(job, client)
                elif job.status == "pending":
                    values = self._do_ots_stamp(job)
                else:
                    values = self._do_ots_upgrade(job, client)
        except Exception as e:
            now = utcnow()
            error = f"{type(e).__name__}: {e}"[:1000]
            values = {
                "attempts": Stamp.attempts + 1,
                "last_error": error,
                "next_attempt_at": now + backoff(job.attempts + 1, now - job.created_at),
            }
            if job.kind == "rfc3161" and isinstance(e, tsa.StampError) and e.permanent:
                values["status"] = "failed"
            log.warning("marca %s/%s fallita (%d): %s", job.provider, job.id, job.attempts + 1, error)
        # 3. scrittura, solo se nel frattempo nessuno ha cambiato lo stato della marca
        with self.db.session() as s:
            s.execute(update(Stamp).where(Stamp.id == job.id, Stamp.status == job.status).values(**values))
            s.commit()

    def _do_rfc3161(self, job: _Job, client: httpx.Client) -> dict:
        digest = bytes.fromhex(job.digest)
        req, nonce = tsa.build_request(digest)
        resp = tsa.request_stamp(tsa.PROVIDERS[job.provider], req, client)
        info = tsa.verify_response(
            job.provider, resp, digest, nonce, now=utcnow(), max_skew_s=self.settings.max_gen_time_skew_s
        )
        return {
            "request": req,
            "token": resp,
            "gen_time": info.gen_time,
            "accuracy_ms": info.accuracy_ms,
            "info_json": json.dumps(
                {
                    "policy": info.policy,
                    "serial": str(info.serial),
                    "signer": info.signer,
                    "nonce": str(nonce),
                    "qualified_verified": info.qualified,
                    "tl_identity": info.tl_identity,
                }
            ),
            "revinfo_json": json.dumps(info.revinfo) if info.revinfo else None,
            "status": "complete",
            "completed_at": utcnow(),
            "attempts": Stamp.attempts + 1,
            "last_error": None,
        }

    def _do_ots_stamp(self, job: _Job) -> dict:
        proof, accepted = ots.stamp(bytes.fromhex(job.digest), self.settings.ots_calendars)
        return {
            "token": proof,
            "info_json": json.dumps({"calendars": accepted}),
            "status": "upgrading",
            "attempts": Stamp.attempts + 1,
            "last_error": None,
            "next_attempt_at": utcnow() + timedelta(seconds=self.settings.ots_upgrade_interval_s),
        }

    def _do_ots_upgrade(self, job: _Job, client: httpx.Client) -> dict:
        res = ots.upgrade(job.token, self.settings.ots_upgrade_whitelist, self.settings.bitcoin_explorer, client)
        values = {"token": res.ots, "last_error": "; ".join(res.errors)[:1000] or None}
        if res.complete:
            info = json.loads(job.info_json or "{}")
            info.update(block_height=res.block_height, block_hash=res.block_hash, block_header=res.block_header)
            values.update(
                status="complete",
                completed_at=utcnow(),
                gen_time=datetime.fromtimestamp(res.block_time, tz=utcnow().tzinfo),
                info_json=json.dumps(info),
            )
        else:
            values["next_attempt_at"] = utcnow() + timedelta(seconds=self.settings.ots_upgrade_interval_s)
        return values

    def retention(self, now: datetime) -> None:
        """Al massimo una volta l'ora: cancella i video oltre la conservazione (mai le prove) e i file rimasti."""
        if self._last_retention and now - self._last_retention < timedelta(hours=1):
            return
        with self.db.session() as s:
            n = services.retention_sweep(s, self.settings, now)
        with self.db.session() as s:
            leftovers = services.cleanup_files(s, self.settings, now)
        evidence.prune_cache(self.settings)
        self._last_retention = now  # solo dopo un giro riuscito: un errore temporaneo si riprova al giro dopo
        if n:
            log.info("conservazione: cancellati %d video scaduti", n)
        if leftovers:
            log.info("conservazione: cancellati %d file rimasti (video già cancellati, upload interrotti)", leftovers)

    # --- mail di contestazione -----------------------------------------------------------------------
    def _schedule_mails(self, now: datetime, wait: bool = False) -> None:
        with self._lock:
            if self._mail_in_flight:
                return
        with self.db.session() as s:
            ids = s.scalars(
                select(ContestNotice.id)
                .where(ContestNotice.status.in_(("pending", "retrying")), ContestNotice.next_attempt_at <= now)
                .order_by(ContestNotice.id)
                .limit(10)
            ).all()
        if not ids:
            return
        with self._lock:
            self._mail_in_flight = True
        future = self._mail_pool.submit(self._send_mails, list(ids))
        if wait:
            future.result()

    def _send_mails(self, ids: list[int]) -> None:
        try:
            for notice_id in ids:
                try:
                    self.send_notice(notice_id)
                except Exception:
                    log.exception("mail di contestazione %s", notice_id)
        finally:
            with self._lock:
                self._mail_in_flight = False

    def send_notice(self, notice_id: int) -> None:
        """Invia una mail di contestazione. Nessuna transazione aperta durante la rete; l'esito si scrive solo se
        nel frattempo lo stato non è cambiato."""
        with self.db.session() as s:
            n = s.get(ContestNotice, notice_id)
            if n is None or n.status not in ("pending", "retrying"):
                return
            status, attempts, created = n.status, n.attempts, n.created_at
            recipients = json.loads(n.recipients_json)
            msg = mailer.build_message(self.settings, recipients, mailer.Mail(n.subject, n.body), n.reply_to)
        now = utcnow()
        try:
            refused = mailer.send(self.settings, msg)
            values = {
                "status": "sent", "sent_at": utcnow(), "message_id": msg["Message-ID"], "attempts": attempts + 1,
                "error": f"rifiutati: {', '.join(f'{a} ({c})' for a, (c, _) in refused.items())}" if refused else None,
            }
            log.info("mail di contestazione %s inviata a %d destinatari", notice_id, len(recipients) - len(refused))
        except mailer.PermanentError as e:
            values = {"status": "failed", "attempts": attempts + 1, "error": f"{type(e).__name__}: {e}"[:1000]}
            log.error("mail di contestazione %s rifiutata: %s", notice_id, e)
        except Exception as e:  # rete, autenticazione, 4xx: si riprova
            values = {
                "status": "retrying", "attempts": attempts + 1, "error": f"{type(e).__name__}: {e}"[:1000],
                "next_attempt_at": now + backoff(attempts + 1, now - created),
            }
            log.warning("mail di contestazione %s non inviata, si riprova: %s", notice_id, type(e).__name__)
        with self.db.session() as s:
            s.execute(
                update(ContestNotice).where(ContestNotice.id == notice_id, ContestNotice.status == status).values(**values)
            )
            s.commit()

    def _check_instance_lock(self, now: datetime) -> None:
        if self._last_lock_check and now - self._last_lock_check < timedelta(minutes=1):
            return
        self._last_lock_check = now
        self.instance_lock_ok = self.db.check_instance_lock()

    # --- controlli periodici ------------------------------------------------------------------------
    def periodic_checks(self, now: datetime) -> None:
        """Trusted List ogni tl_check_interval_h; archivio una volta al giorno dopo archive_check_local_hour.

        Dopo un errore si riprova fra un'ora. Una verifica dell'archivio iniziata e mai conclusa (processo ucciso,
        memoria) non si ripete lo stesso giorno: la segnala problems.py.
        """
        with self.db.session() as s:
            tl = services.get_json_setting(s, checks.TL_KEY) or {}
            arch = services.get_json_setting(s, checks.ARCHIVE_KEY) or {}
        tl_at = datetime.fromisoformat(tl["at"]) if tl.get("at") else None
        every = timedelta(hours=1 if tl.get("error") else self.settings.tl_check_interval_h)
        if tl_at is None or now - tl_at >= every:
            self._submit_check("tl", lambda: checks.run_trusted_list(self.db, self.settings))
        with self.db.session() as s:
            rev = services.get_json_setting(s, checks.REVOCATION_KEY) or {}
        rev_at = datetime.fromisoformat(rev["at"]) if rev.get("at") else None
        every = timedelta(hours=1 if rev.get("error") else self.settings.attestation_revocation_check_h)
        if rev_at is None or now - rev_at >= every:
            self._submit_check("revoche", lambda: checks.run_attestation_revocations(self.db, self.settings))

        local = now.astimezone(LOCAL_TZ)
        if local.hour < self.settings.archive_check_local_hour:
            return
        last = max((datetime.fromisoformat(arch[k]) for k in ("at", "started_at") if arch.get(k)), default=None)
        if last is None or last.astimezone(LOCAL_TZ).date() != local.date():
            self._submit_check("archive", self._archive_check)
        elif arch.get("error") and arch.get("at") and now - datetime.fromisoformat(arch["at"]) >= timedelta(hours=1):
            # si riprova solo se l'ultima verifica partita si è anche conclusa: una ripartita e mai finita (processo
            # ucciso per memoria) non va rilanciata a ogni giro, la segnala problems.py
            started = datetime.fromisoformat(arch["started_at"]) if arch.get("started_at") else None
            if started is None or started <= datetime.fromisoformat(arch["at"]):
                self._submit_check("archive", self._archive_check)

    def _archive_check(self) -> None:
        record = checks.run_archive(self.db, self.settings)
        if record.get("error"):
            log.error("verifica dell'archivio non riuscita: %s", record["error"])
        elif not record["ok"]:
            log.error("verifica dell'archivio: %s", "; ".join(record["errors"][:5]))

    def _submit_check(self, name: str, fn) -> None:
        with self._lock:
            if name in self._checks_in_flight:
                return
            self._checks_in_flight.add(name)

        def run():
            try:
                fn()
            except Exception:
                log.exception("controllo periodico %s", name)
            finally:
                with self._lock:
                    self._checks_in_flight.discard(name)

        self._check_pool.submit(run)

    # --- catena --------------------------------------------------------------------------------
    def _maybe_schedule_chain(self, now: datetime) -> None:
        """Il lavoro cambia una volta al giorno: si ricalcola ogni 10 minuti o al cambio di data, non ogni 5 s."""
        today = now.astimezone(LOCAL_TZ).date()
        if self._last_chain and now - self._last_chain[0] < timedelta(minutes=10) and self._last_chain[1] == today:
            return
        self.schedule_daily_chain_stamps(now)
        self._last_chain = (now, today)

    def schedule_daily_chain_stamps(self, now: datetime) -> None:
        """Marca la testa della catena a fine giornata (ora italiana) per ogni giorno concluso.

        - si parte solo `chain_stamp_grace_minutes` dopo mezzanotte (timbrature a cavallo registrate)
        - si recuperano i giorni mancanti fino a `chain_stamp_catchup_days` indietro
        - TSA: tutte le qualificate di tsa_providers, in parallelo (se una non risponde, l'altra copre il giorno
          subito), più OTS
        """
        local = now.astimezone(LOCAL_TZ) - timedelta(minutes=self.settings.chain_stamp_grace_minutes)
        last_day = local.date() - timedelta(days=1)
        qualified = [p for p in self.settings.tsa_providers if tsa.PROVIDERS[p].qualified]
        with self.db.session() as s:
            first = s.scalar(select(func.min(Punch.server_received_at)))
            if first is None:
                return
            day = max(first.astimezone(LOCAL_TZ).date(), last_day - timedelta(days=self.settings.chain_stamp_catchup_days))
            while day <= last_day:
                self._stamp_chain_day(s, day, qualified)
                day += timedelta(days=1)
            s.commit()

    def _stamp_chain_day(self, s, day, qualified: list[str]) -> None:
        end_of_day = datetime.combine(day + timedelta(days=1), time.min, LOCAL_TZ)
        head = s.scalar(
            select(Punch.chain_hash).where(Punch.server_received_at < end_of_day).order_by(Punch.id.desc()).limit(1)
        )
        if head is None:
            return
        existing = set(s.scalars(select(Stamp.provider).where(Stamp.chain_day == day)))
        if self.settings.ots_enabled and "ots" not in existing:
            s.add(Stamp(target="chain", chain_day=day, provider="ots", kind="ots", digest=head))
        for p in qualified:
            if p not in existing:
                s.add(Stamp(target="chain", chain_day=day, provider=p, kind="rfc3161", qualified=True, digest=head))

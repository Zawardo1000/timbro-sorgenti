import logging
from collections.abc import Iterator
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import Connection, DateTime, Engine, MetaData, create_engine, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.types import TypeDecorator

# Chiavi dei lock "advisory" di Postgres (numeri qualsiasi, purché fissi e diversi tra loro)
INSTANCE_LOCK = 0x74696D62726F01  # un solo processo Timbro per database (worker e catena)
CHAIN_LOCK = 0x74696D62726F02  # estensione della catena, una alla volta
MIGRATE_LOCK = 0x74696D62726F03  # migrazioni, una alla volta

log = logging.getLogger(__name__)


def utcnow() -> datetime:
    return datetime.now(UTC)


class UTCDateTime(TypeDecorator):
    """timestamptz di Postgres: rifiuta le date senza fuso e le rilegge sempre in UTC."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("datetime senza fuso orario")
        return value.astimezone(UTC)

    def process_result_value(self, value: datetime | None, dialect):
        return None if value is None else value.astimezone(UTC)


class Base(DeclarativeBase):
    # vincoli con nome: le migrazioni li ritrovano per modificarli
    metadata = MetaData(
        naming_convention={
            "ix": "ix_%(column_0_label)s",
            "uq": "uq_%(table_name)s_%(column_0_N_name)s",
            "ck": "ck_%(table_name)s_%(constraint_name)s",
            "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
            "pk": "pk_%(table_name)s",
        }
    )


def make_engine(url: str) -> Engine:
    # Postgres è condiviso con altri servizi: poche connessioni, verificate prima dell'uso (riavvii del DB)
    return create_engine(url, pool_size=5, max_overflow=10, pool_pre_ping=True, pool_recycle=3600)


class InstanceLockError(RuntimeError):
    pass


class Database:
    def __init__(self, url: str):
        self.engine = make_engine(url)
        self.sessions = sessionmaker(self.engine, expire_on_commit=False)
        self._instance_conn: Connection | None = None

    def _alembic(self):
        from alembic.config import Config
        from alembic.script import ScriptDirectory

        cfg = Config()
        cfg.set_main_option("script_location", str(Path(__file__).parent / "migrations"))
        return cfg, ScriptDirectory.from_config(cfg)

    def schema_state(self, conn: Connection | None = None) -> str:
        """"aggiornato", "indietro" (mancano migrazioni) o "piu-nuovo" (schema di una versione più recente del codice:
        succede dopo un ritorno automatico alla versione precedente o un git revert)."""
        from alembic.runtime.migration import MigrationContext

        _, script = self._alembic()
        known = {r.revision for r in script.walk_revisions()}
        if conn is None:
            with self.engine.connect() as c:
                return self.schema_state(c)
        current = set(MigrationContext.configure(conn).get_current_heads())
        if current - known:
            return "piu-nuovo"
        return "aggiornato" if current == set(script.get_heads()) else "indietro"

    def migrate(self) -> str:
        """Porta lo schema all'ultima migrazione Alembic (lo schema si crea solo così) e ne restituisce lo stato.

        - Un lock advisory serializza le migrazioni (app che parte e comando "migrate" lanciato a mano).
        - Se lo schema è più nuovo del codice (ritorno alla versione precedente dopo una migrazione già applicata),
          non tocca niente e lo segnala: l'app parte comunque, perché le migrazioni aggiungono e non tolgono
          (regola in CLAUDE.md). Si torna indietro con una migrazione nuova, mai con git revert di una migrazione.
        """
        from alembic import command

        cfg, _ = self._alembic()
        with self.engine.begin() as conn:
            conn.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": MIGRATE_LOCK})
            conn.execute(text("SET LOCAL lock_timeout = '15s'"))  # mai in coda per sempre dietro un'altra sessione
            state = self.schema_state(conn)
            if state == "indietro":
                cfg.attributes["connection"] = conn
                command.upgrade(cfg, "head")
                state = "aggiornato"
        if state == "piu-nuovo":
            log.warning("schema del database più nuovo del codice: nessuna migrazione applicata")
        return state

    def acquire_instance_lock(self) -> None:
        """Un solo processo per database: catena e worker non si coordinano fra processi diversi.

        Lock di sessione di Postgres, tenuto su una connessione dedicata finché l'app è attiva: se il
        processo muore, Postgres lo rilascia da solo.
        """
        conn = self.engine.connect()
        try:
            got = conn.scalar(text("SELECT pg_try_advisory_lock(:k)"), {"k": INSTANCE_LOCK})
            conn.commit()
        except Exception:
            conn.close()
            raise
        if not got:
            conn.close()
            raise InstanceLockError("un altro processo Timbro sta già usando questo database")
        self._instance_conn = conn

    def check_instance_lock(self) -> bool:
        """Il lock d'istanza c'è ancora? Se la connessione è caduta (es. Postgres riavviato) prova a riprenderlo."""
        conn = self._instance_conn
        if conn is not None:
            try:
                held = conn.scalar(
                    text(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()"
                        " AND classid = :hi AND objid = :lo AND objsubid = 1"
                    ),
                    {"hi": INSTANCE_LOCK >> 32, "lo": INSTANCE_LOCK & 0xFFFFFFFF},
                )
                conn.commit()
                if held:
                    return True
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass
            self._instance_conn = None
        try:
            self.acquire_instance_lock()
            log.warning("lock d'istanza perso (connessione caduta?) e ripreso")
            return True
        except Exception as e:
            log.error("lock d'istanza perso e non ripreso: %s", e)
            return False

    def release_instance_lock(self) -> None:
        conn, self._instance_conn = self._instance_conn, None
        if conn is None:
            return
        try:
            conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": INSTANCE_LOCK})
            conn.commit()
        except Exception:
            pass  # connessione già caduta: Postgres ha già liberato il lock
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def session(self) -> Session:
        return self.sessions()

    def dependency(self) -> Iterator[Session]:
        with self.sessions() as s:
            yield s

    def dispose(self) -> None:
        try:
            self.release_instance_lock()
        finally:
            self.engine.dispose()

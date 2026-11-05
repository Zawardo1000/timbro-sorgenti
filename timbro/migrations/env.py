"""Ambiente Alembic. Il database viene da:
1. una connessione passata da Database.migrate() in config.attributes["connection"];
2. altrimenti da DATABASE_URL, come fa l'app (per `alembic revision --autogenerate`, vedi CLAUDE.md).
"""

from alembic import context

from timbro import models  # noqa: F401  registra le tabelle
from timbro.config import get_settings
from timbro.db import Base, make_engine

config = context.config
target_metadata = Base.metadata


def _configure(**kw) -> None:
    context.configure(target_metadata=target_metadata, compare_type=True, **kw)


def run_migrations_offline() -> None:
    _configure(url=get_settings().database_url, literal_binds=True, dialect_opts={"paramstyle": "named"})
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connection = config.attributes.get("connection")
    if connection is not None:
        _configure(connection=connection)
        with context.begin_transaction():
            context.run_migrations()
        return
    engine = make_engine(get_settings().database_url)
    with engine.connect() as connection:
        _configure(connection=connection)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()

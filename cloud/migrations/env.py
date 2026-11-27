"""Alembic: versioned changes to the database structure.
Uses the same DATABASE_URL as the app and the SQLModel tables in app/tables.py."""
from logging.config import fileConfig

from alembic import context
from sqlmodel import SQLModel

from app import tables  # noqa: F401  (registers all tables on SQLModel.metadata)
from app.db import engine

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = SQLModel.metadata


def run_migrations_offline() -> None:
    """'alembic upgrade head --sql': print the SQL instead of running it."""
    context.configure(url=str(engine.url), target_metadata=target_metadata, literal_binds=True,
                      render_as_batch=True)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    with engine.connect() as connection:
        # render_as_batch: SQLite can't ALTER most columns; batch mode rebuilds the table instead.
        context.configure(connection=connection, target_metadata=target_metadata, render_as_batch=True)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()

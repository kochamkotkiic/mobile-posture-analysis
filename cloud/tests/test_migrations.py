"""The migrations must create exactly the tables defined in app/tables.py.
If someone changes a table and forgets to write a migration, this test fails."""
import os
import tempfile
from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect
from sqlmodel import SQLModel

from app import tables  # noqa: F401

CLOUD_DIR = Path(__file__).resolve().parents[1]


def alembic_config(url: str) -> Config:
    cfg = Config(str(CLOUD_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(CLOUD_DIR / "migrations"))
    return cfg


def test_migrations_match_models(monkeypatch):
    url = "sqlite:///" + os.path.join(tempfile.mkdtemp(), "migrations.db")
    engine = create_engine(url)
    monkeypatch.setattr("app.db.engine", engine)          # migrations/env.py uses app.db.engine

    command.upgrade(alembic_config(url), "head")
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn, opts={"render_as_batch": True}),
                                SQLModel.metadata)
    assert diff == [], f"tables changed without a migration: {diff}"

    # and it can be undone completely
    command.downgrade(alembic_config(url), "base")
    assert set(inspect(engine).get_table_names()) <= {"alembic_version"}

from pathlib import Path

from sqlmodel import SQLModel, Session, create_engine

from .config import settings

CLOUD_DIR = Path(__file__).resolve().parents[1]

connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, connect_args=connect_args, pool_pre_ping=True)


def run_migrations():
    """Bring the database structure up to date: applies every migration in migrations/versions/
    that this database hasn't had yet (Alembic records the current version in table alembic_version).
    A fresh database gets all tables; an existing one only gets the new changes. Data is kept."""
    from alembic import command
    from alembic.config import Config

    cfg = Config(str(CLOUD_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(CLOUD_DIR / "migrations"))
    command.upgrade(cfg, "head")


def create_tables_for_tests():
    """Tests only: build tables straight from the models (fast). tests/test_migrations.py checks
    that the migrations produce exactly the same structure."""
    SQLModel.metadata.create_all(engine)


def get_session():
    with Session(engine) as session:
        yield session

"""Database tables. Every row refers to users by their Firebase uid; no passwords are stored here.
All datetimes are timezone-aware UTC."""
from datetime import datetime, timezone
from sqlmodel import SQLModel, Field, JSON, Column


# Fixed names for constraints and indexes, so later migrations can find and change them
# (otherwise each database invents its own names).
SQLModel.metadata.naming_convention = {
    "ix": "ix_%(table_name)s_%(column_0_name)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def to_utc(dt: datetime) -> datetime:
    """Times without a timezone are assumed to be UTC."""
    return dt.astimezone(timezone.utc) if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


class User(SQLModel, table=True):
    uid: str = Field(primary_key=True)                 # Firebase uid
    email: str | None = Field(default=None, index=True)
    display_name: str | None = None
    fcm_token: str | None = None                       # where push notifications go
    created_at: datetime = Field(default_factory=utcnow)


class Calibration(SQLModel, table=True):
    """New row per calibration; the newest one is the current one (keeps history)."""
    id: int | None = Field(default=None, primary_key=True)
    user_uid: str = Field(foreign_key="user.uid", index=True)
    values: dict = Field(sa_column=Column(JSON))       # the 9 keys nose_x ... right_shoulder_z
    created_at: datetime = Field(default_factory=utcnow)


class AlertEvent(SQLModel, table=True):
    """The phone's 10-minute continuous bad posture alert."""
    id: int | None = Field(default=None, primary_key=True)
    user_uid: str = Field(foreign_key="user.uid", index=True)
    fired_at: datetime                                 # phone clock
    received_at: datetime = Field(default_factory=utcnow)  # server clock
    notified_count: int = 0                            # how many friends got a push


class Friendship(SQLModel, table=True):
    """One row per pair. requester sent the request, addressee accepts it.
    Each side marks the other as favorite independently: a user's alert notifies the friends
    that user marked as favorite."""
    id: int | None = Field(default=None, primary_key=True)
    requester_uid: str = Field(foreign_key="user.uid", index=True)
    addressee_uid: str = Field(foreign_key="user.uid", index=True)
    pair_key: str = Field(unique=True)                 # UNIQUE, so the database itself refuses a second row for the same pair in either direction
    status: str = "pending"                            # pending | accepted
    requester_favorite: bool = False                   # requester marked addressee as favorite
    addressee_favorite: bool = False                   # addressee marked requester as favorite
    created_at: datetime = Field(default_factory=utcnow)


def make_pair_key(a: str, b: str) -> str:
    return "|".join(sorted((a, b)))


class Task(SQLModel, table=True):
    """Friend asks the monitored user to hold good posture for 5 s; the cloud verifies it."""
    id: int | None = Field(default=None, primary_key=True)
    from_uid: str = Field(foreign_key="user.uid", index=True)   # friend who sent it
    to_uid: str = Field(foreign_key="user.uid", index=True)     # monitored user
    kind: str = "hold_good_5s"
    status: str = "pending"                            # pending | passed | failed | expired
    created_at: datetime = Field(default_factory=utcnow)
    expires_at: datetime
    completed_at: datetime | None = None
    n_samples: int | None = None
    good_fraction: float | None = None

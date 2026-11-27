import json
import os
import tempfile
from pathlib import Path

# Must be set before the app is imported: temp database, no real Firebase, dev tokens allowed.
# TEST_DATABASE_URL lets the same tests run against a real Postgres (a SEPARATE test database:
# every test drops and recreates all tables). Default: a temporary SQLite file.
os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL", "sqlite:///" + os.path.join(tempfile.mkdtemp(), "test.db"))
os.environ["FIREBASE_ENABLED"] = "false"
os.environ["DEV_AUTH"] = "true"
os.environ["MIGRATE_ON_STARTUP"] = "false"   # tests build tables from the models; migrations tested separately

import pytest
from fastapi.testclient import TestClient
from sqlmodel import SQLModel

from app import push
from app.db import engine
from app.main import app

CASES = json.loads((Path(__file__).resolve().parents[2] / "models" / "onnx_test_cases.json").read_text())

CAL = {k: 0.1 for k in ["nose_x", "nose_y", "nose_z", "left_shoulder_x", "left_shoulder_y",
                        "left_shoulder_z", "right_shoulder_x", "right_shoulder_y", "right_shoulder_z"]}


def token(uid: str) -> dict:
    return {"Authorization": f"Bearer dev:{uid}:{uid}@test.com"}


A, B, C = token("alice"), token("bob"), token("carol")


@pytest.fixture
def pushes(monkeypatch):
    """Records push notifications instead of sending them; every push 'succeeds'."""
    sent = []

    def fake_send(fcm_token, title, body, data=None):
        if not fcm_token:
            return False
        sent.append({"to": fcm_token, "title": title, "data": data or {}})
        return True

    monkeypatch.setattr(push, "send", fake_send)
    return sent


@pytest.fixture
def client(pushes):
    SQLModel.metadata.drop_all(engine)
    SQLModel.metadata.create_all(engine)
    with TestClient(app) as c:
        yield c


@pytest.fixture
def users(client):
    """alice, bob, carol registered, each with an FCM token = 'fcm-<name>'."""
    for name, h in (("alice", A), ("bob", B), ("carol", C)):
        assert client.put("/me", headers=h, json={"display_name": name.title()}).status_code == 200
        assert client.put("/me/fcm-token", headers=h, json={"token": f"fcm-{name}"}).status_code == 200
    return client


@pytest.fixture
def friends_ab(users):
    """alice and bob are accepted friends; alice marked bob as favorite."""
    c = users
    fid = c.post("/friends", headers=A, json={"email": "bob@test.com"}).json()["id"]
    assert c.post(f"/friends/{fid}/accept", headers=B).status_code == 200
    assert c.put(f"/friends/{fid}/favorite", headers=A, json={"favorite": True}).status_code == 200
    return c

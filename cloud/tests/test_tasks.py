from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.tables import Task

from .conftest import A, B, C, CASES

GOOD = [c["input"] for c in CASES["cases"] if c["probabilities"][0] >= c["probabilities"][1]]
BAD = [c["input"] for c in CASES["cases"] if c["probabilities"][1] > c["probabilities"][0]]


def make_task(c):
    r = c.post("/tasks", headers=B, json={"to_uid": "alice"})   # bob challenges alice
    assert r.status_code == 201
    return r.json()["id"]


def test_only_friends_can_send_tasks(friends_ab):
    assert friends_ab.post("/tasks", headers=C, json={"to_uid": "alice"}).status_code == 404


def test_task_passes_with_good_posture(friends_ab, pushes):
    c = friends_ab
    tid = make_task(c)
    assert pushes[-1]["to"] == "fcm-alice" and pushes[-1]["data"]["type"] == "task"
    assert c.get("/tasks", headers=A).json()[0]["id"] == tid

    samples = (GOOD * 3)[:20]
    r = c.post(f"/tasks/{tid}/result", headers=A, json={"samples": samples})
    assert r.status_code == 200
    assert r.json()["status"] == "passed" and r.json()["good_fraction"] == 1.0
    assert pushes[-1]["to"] == "fcm-bob" and pushes[-1]["data"]["status"] == "passed"
    assert c.get(f"/tasks/{tid}", headers=B).json()["status"] == "passed"
    # can't answer twice
    assert c.post(f"/tasks/{tid}/result", headers=A, json={"samples": samples}).status_code == 409


def test_task_fails_with_bad_posture(friends_ab):
    c = friends_ab
    tid = make_task(c)
    r = c.post(f"/tasks/{tid}/result", headers=A, json={"samples": (BAD * 3)[:20]})
    assert r.json()["status"] == "failed"


def test_task_fails_with_too_few_samples(friends_ab):
    c = friends_ab
    tid = make_task(c)
    assert c.post(f"/tasks/{tid}/result", headers=A, json={"samples": GOOD[:5]}).json()["status"] == "failed"


def test_only_target_can_answer(friends_ab):
    c = friends_ab
    tid = make_task(c)
    assert c.post(f"/tasks/{tid}/result", headers=B, json={"samples": GOOD}).status_code == 404
    assert c.get(f"/tasks/{tid}", headers=C).status_code == 404


def test_task_expires(friends_ab):
    c = friends_ab
    tid = make_task(c)
    with Session(engine) as s:
        t = s.get(Task, tid)
        t.expires_at = t.created_at - timedelta(seconds=1)
        s.add(t); s.commit()
    assert c.get(f"/tasks/{tid}", headers=A).json()["status"] == "expired"
    assert c.post(f"/tasks/{tid}/result", headers=A, json={"samples": GOOD}).status_code == 409

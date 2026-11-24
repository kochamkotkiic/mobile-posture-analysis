"""Friend task: "hold good posture for 5 seconds".
The friend creates it -> monitored user gets a push -> the phone records ~5 s of normalized
feature samples and uploads them -> the CLOUD classifies them with the same ONNX model and decides
pass/fail -> the friend gets a push with the result."""
from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from .. import push
from ..config import settings
from ..db import get_session
from ..tables import Task, User, to_utc, utcnow
from .deps import registered_user
from .friends import are_friends

router = APIRouter(prefix="/tasks", tags=["tasks"])

Sample = list[float]


class TaskIn(BaseModel):
    to_uid: str


class TaskResultIn(BaseModel):
    samples: list[Sample] = Field(min_length=1, max_length=200)   # normalized features, 9 each


def expire_if_needed(t: Task, s: Session) -> Task:
    if t.status == "pending" and utcnow() > to_utc(t.expires_at):
        t.status = "expired"
        s.add(t); s.commit(); s.refresh(t)
    return t


@router.post("", status_code=201)
def create_task(body: TaskIn, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    target = s.get(User, body.to_uid)
    if not target or not are_friends(s, me.uid, target.uid):
        raise HTTPException(404, "not a friend")
    t = Task(from_uid=me.uid, to_uid=target.uid,
             expires_at=utcnow() + timedelta(seconds=settings.task_ttl_seconds))
    s.add(t); s.commit(); s.refresh(t)
    push.send(target.fcm_token, "Posture challenge",
              f"{me.display_name or me.email} challenges you: hold good posture for 5 seconds",
              {"type": "task", "task_id": t.id})
    return t


@router.get("")
def list_tasks(role: Literal["received", "sent"] = "received", me: User = Depends(registered_user),
               s: Session = Depends(get_session)):
    col = Task.to_uid if role == "received" else Task.from_uid
    rows = s.exec(select(Task).where(col == me.uid).order_by(Task.created_at.desc()).limit(100)).all()
    return [expire_if_needed(t, s) for t in rows]


@router.get("/{task_id}")
def get_task(task_id: int, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    t = s.get(Task, task_id)
    if not t or me.uid not in (t.from_uid, t.to_uid):
        raise HTTPException(404, "task not found")
    return expire_if_needed(t, s)


@router.post("/{task_id}/result")
def submit_result(task_id: int, body: TaskResultIn, request: Request,
                  me: User = Depends(registered_user), s: Session = Depends(get_session)):
    t = s.get(Task, task_id)
    if not t or t.to_uid != me.uid:                 # only the challenged user can answer
        raise HTTPException(404, "task not found")
    expire_if_needed(t, s)
    if t.status != "pending":
        raise HTTPException(409, f"task is already {t.status}")
    if any(len(x) != 9 for x in body.samples):
        raise HTTPException(422, "each sample must have 9 values")

    predictions = request.app.state.model.predict(body.samples)
    good = sum(1 for p in predictions if p["label"] == 0)
    t.n_samples = len(predictions)
    t.good_fraction = good / len(predictions)
    passed = (t.n_samples >= settings.task_min_samples
              and t.good_fraction >= settings.task_min_good_fraction)
    t.status = "passed" if passed else "failed"
    t.completed_at = utcnow()
    s.add(t); s.commit(); s.refresh(t)

    sender = s.get(User, t.from_uid)
    push.send(sender.fcm_token, "Challenge " + ("completed" if passed else "failed"),
              f"{me.display_name or me.email} {'held' if passed else 'did not hold'} good posture",
              {"type": "task_result", "task_id": t.id, "status": t.status})
    return t

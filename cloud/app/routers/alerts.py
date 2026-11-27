from datetime import datetime

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlmodel import Session, select

from .. import push
from ..db import get_session
from ..tables import AlertEvent, User, to_utc
from .deps import registered_user
from .friends import favorite_friend_uids

router = APIRouter(prefix="/alerts", tags=["alerts"])


class AlertIn(BaseModel):
    fired_at: datetime      # ISO 8601 with timezone, e.g. "2026-11-24T15:30:00Z"


@router.post("", status_code=201)
def report_alert(body: AlertIn, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    """The phone reports a LongBadPostureAlert (10 min continuous bad posture).
    Stored, then every friend the user marked as favorite gets a push."""
    event = AlertEvent(user_uid=me.uid, fired_at=to_utc(body.fired_at))
    s.add(event); s.commit(); s.refresh(event)

    notified = []
    for friend_uid in favorite_friend_uids(s, me.uid):
        friend = s.get(User, friend_uid)
        if push.send(friend.fcm_token, "Posture alert",
                     f"{me.display_name or me.email} has been slouching for 10 minutes",
                     {"type": "posture_alert", "user_uid": me.uid, "alert_id": event.id}):
            notified.append(friend_uid)

    event.notified_count = len(notified)
    s.add(event); s.commit(); s.refresh(event)
    return {"id": event.id, "fired_at": event.fired_at, "received_at": event.received_at,
            "notified": notified}


@router.get("")
def my_alerts(limit: int = 50, me: User = Depends(registered_user), s: Session = Depends(get_session)):
    return s.exec(select(AlertEvent).where(AlertEvent.user_uid == me.uid)
                  .order_by(AlertEvent.fired_at.desc()).limit(min(limit, 500))).all()

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..auth import Caller, current_caller
from ..db import get_session
from ..tables import User, Calibration
from .deps import registered_user

router = APIRouter(prefix="/me", tags=["me"])

# Same keys as the Python profiles (data/profiles/*.json) and the Kotlin Calibration
CALIBRATION_KEYS = ["nose_x", "nose_y", "nose_z",
                    "left_shoulder_x", "left_shoulder_y", "left_shoulder_z",
                    "right_shoulder_x", "right_shoulder_y", "right_shoulder_z"]


class ProfileIn(BaseModel):
    display_name: str | None = None


class FcmTokenIn(BaseModel):
    token: str = Field(min_length=1)


def public_profile(u: User) -> dict:
    return {"uid": u.uid, "email": u.email, "display_name": u.display_name}


@router.put("")
def upsert_me(body: ProfileIn, caller: Caller = Depends(current_caller), s: Session = Depends(get_session)):
    """Call once after every sign-in. Creates the user row or updates it. Email comes from the token."""
    user = s.get(User, caller.uid) or User(uid=caller.uid)
    if caller.email:
        user.email = caller.email.lower()
    if body.display_name is not None:
        user.display_name = body.display_name
    s.add(user); s.commit(); s.refresh(user)
    return public_profile(user)


@router.get("")
def get_me(user: User = Depends(registered_user)):
    return public_profile(user)


@router.put("/fcm-token")
def set_fcm_token(body: FcmTokenIn, user: User = Depends(registered_user), s: Session = Depends(get_session)):
    """The app sends its FCM registration token (and again whenever Firebase rotates it)."""
    user.fcm_token = body.token
    s.add(user); s.commit()
    return {"ok": True}


@router.put("/calibration")
def save_calibration(values: dict[str, float], user: User = Depends(registered_user),
                     s: Session = Depends(get_session)):
    if sorted(values) != sorted(CALIBRATION_KEYS):
        raise HTTPException(422, f"calibration needs exactly these keys: {CALIBRATION_KEYS}")
    cal = Calibration(user_uid=user.uid, values=values)
    s.add(cal); s.commit(); s.refresh(cal)
    return cal


@router.get("/calibration")
def latest_calibration(user: User = Depends(registered_user), s: Session = Depends(get_session)):
    cal = s.exec(select(Calibration).where(Calibration.user_uid == user.uid)
                 .order_by(Calibration.created_at.desc(), Calibration.id.desc())).first()
    if not cal:
        raise HTTPException(404, "no calibration yet")
    return cal

from fastapi import Depends, HTTPException
from sqlmodel import Session

from ..auth import Caller, current_caller
from ..db import get_session
from ..tables import User


def registered_user(caller: Caller = Depends(current_caller), s: Session = Depends(get_session)) -> User:
    """The calling user's row. The app must call PUT /me once after sign-in."""
    user = s.get(User, caller.uid)
    if not user:
        raise HTTPException(404, "user not registered: call PUT /me first")
    return user

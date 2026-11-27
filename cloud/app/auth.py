"""Who is calling. The phone signs in with Firebase Auth and sends its ID token as
'Authorization: Bearer <token>'. We verify the token's signature with firebase-admin.
The uid always comes from the token, never from the request body, so nobody can act as someone else."""
from dataclasses import dataclass

import firebase_admin
from firebase_admin import auth as fb_auth
from fastapi import Header, HTTPException

from .config import settings


@dataclass
class Caller:
    uid: str
    email: str | None = None


def init_firebase():
    """Locally: reads the service-account file from GOOGLE_APPLICATION_CREDENTIALS.
    On Cloud Run: uses the service's own credentials automatically."""
    if settings.firebase_enabled and not firebase_admin._apps:
        firebase_admin.initialize_app()


def current_caller(authorization: str = Header(default="")) -> Caller:
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing token")
    token = authorization.removeprefix("Bearer ").strip()

    # Development shortcut so the team can test without Firebase: "dev:<uid>:<email>"
    if settings.dev_auth and token.startswith("dev:"):
        _, uid, *rest = token.split(":", 2)
        if not uid:
            raise HTTPException(401, "dev token needs a uid")
        return Caller(uid=uid, email=rest[0] if rest else None)

    if not settings.firebase_enabled:
        raise HTTPException(401, "Firebase is disabled on this server")
    try:
        init_firebase()
        decoded = fb_auth.verify_id_token(token)
    except Exception:
        raise HTTPException(401, "invalid or expired token")
    return Caller(uid=decoded["uid"], email=decoded.get("email"))

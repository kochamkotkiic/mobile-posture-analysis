"""Push notifications through Firebase Cloud Messaging (FCM).
A failed push never breaks the request: it is logged and reported as not delivered."""
import logging

from .config import settings

log = logging.getLogger("push")


def send(fcm_token: str | None, title: str, body: str, data: dict | None = None) -> bool:
    if not fcm_token:
        return False
    if not settings.firebase_enabled:
        log.info("push skipped (Firebase disabled): %s", title)
        return False
    try:
        from firebase_admin import messaging
        from .auth import init_firebase
        init_firebase()
        messaging.send(messaging.Message(
            token=fcm_token,
            notification=messaging.Notification(title=title, body=body),
            data={k: str(v) for k, v in (data or {}).items()},  # FCM data values must be strings
        ))
        return True
    except Exception as e:  # expired token, network error, ...
        log.warning("push failed: %s", e)
        return False

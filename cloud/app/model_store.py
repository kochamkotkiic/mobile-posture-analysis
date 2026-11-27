"""Where the posture model comes from.

Cloud: the model lives in a Cloud Storage bucket (external cloud storage). At startup the server
downloads it once and uses that file both for /predict and to serve phones via /model/file.
Updating the model = uploading a new file to the bucket (scripts/upload_model.py) and restarting.
Local/tests: no bucket configured -> the file in models/ is used."""
import logging
from pathlib import Path

from .config import settings

log = logging.getLogger("model_store")


def download_from_bucket(bucket: str, obj: str, dest: Path) -> None:
    from google.cloud import storage   # installed with firebase-admin
    dest.parent.mkdir(parents=True, exist_ok=True)
    storage.Client().bucket(bucket).blob(obj).download_to_filename(str(dest))


def resolve_model_path() -> Path:
    if not settings.model_bucket:
        return settings.model_path
    dest = settings.model_cache_path
    log.info("downloading gs://%s/%s -> %s", settings.model_bucket, settings.model_object, dest)
    download_from_bucket(settings.model_bucket, settings.model_object, dest)
    return dest


def source_description() -> str:
    if settings.model_bucket:
        return f"gs://{settings.model_bucket}/{settings.model_object}"
    return "local file"

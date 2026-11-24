"""The posture model is stored and served by the cloud, versioned by its SHA-256.
The app compares the version with the one it has and downloads only when it changed."""
import hashlib
from functools import lru_cache
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse

from ..config import settings

router = APIRouter(prefix="/model", tags=["model"])


@lru_cache
def model_info(path: Path) -> dict:
    data = path.read_bytes()
    return {"name": path.name, "version": hashlib.sha256(data).hexdigest(), "size_bytes": len(data),
            "input": "features float32 [N, 9] (features - calibration)",
            "decision": "use 'probabilities' [P(good), P(bad)], bad if P(bad) > P(good)",
            "url": "/model/file"}


@router.get("")
def get_model_info():
    return model_info(settings.model_path)


@router.get("/file")
def download_model():
    info = model_info(settings.model_path)
    return FileResponse(settings.model_path, media_type="application/octet-stream",
                        filename=info["name"], headers={"ETag": info["version"]})

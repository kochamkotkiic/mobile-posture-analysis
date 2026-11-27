"""The posture model is stored in the cloud and served to phones, versioned by its SHA-256.
The app compares the version with the one it has and downloads only when it changed."""
import hashlib
from functools import lru_cache
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse

from ..model_store import source_description

router = APIRouter(prefix="/model", tags=["model"])


@lru_cache
def model_info(path: Path) -> dict:
    data = path.read_bytes()
    return {"name": "posture_model.onnx", "version": hashlib.sha256(data).hexdigest(),
            "size_bytes": len(data), "source": source_description(),
            "input": "features float32 [N, 9] (features - calibration)",
            "decision": "use 'probabilities' [P(good), P(bad)], bad if P(bad) > P(good)",
            "url": "/model/file"}


@router.get("")
def get_model_info(request: Request):
    return model_info(request.app.state.model_path)


@router.get("/file")
def download_model(request: Request):
    path = request.app.state.model_path
    info = model_info(path)
    return FileResponse(path, media_type="application/octet-stream",
                        filename=info["name"], headers={"ETag": info["version"]})

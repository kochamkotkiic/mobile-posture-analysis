from pathlib import Path
from pydantic_settings import BaseSettings

REPO_ROOT = Path(__file__).resolve().parents[2]

class Settings(BaseSettings):
    model_path: Path = REPO_ROOT / "models" / "posture_model.onnx"
    max_batch: int = 100     # protects the server from huge requests

settings = Settings()
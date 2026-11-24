"""All settings come from environment variables (same code on laptop, PC edge and Cloud Run)."""
from pathlib import Path
from pydantic_settings import BaseSettings

REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_path: Path = REPO_ROOT / "models" / "posture_model.onnx"
    max_batch: int = 100                      # max samples per /predict/batch request

    database_url: str = "sqlite:///./dev.db"  # cloud: postgresql+psycopg://user:pass@host/db

    firebase_enabled: bool = True             # False = no Firebase calls (tests, offline dev)
    dev_auth: bool = False                    # True = accept "Bearer dev:<uid>:<email>" tokens. NEVER in the cloud.

    task_ttl_seconds: int = 600               # a friend task expires after 10 min
    task_min_samples: int = 15                # 5 s at ~4-5 samples/s
    task_min_good_fraction: float = 0.8       # task passes if >= 80% of samples are "good"


settings = Settings()

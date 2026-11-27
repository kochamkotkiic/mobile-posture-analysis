"""Model from Cloud Storage: when a bucket is configured, the server downloads the model from it
and uses that file (no real Google call here: the download is replaced by a copy)."""
import shutil

from fastapi.testclient import TestClient

from app import model_store
from app.config import settings
from app.main import app


def test_model_comes_from_bucket_when_configured(monkeypatch, tmp_path, pushes):
    calls = []

    def fake_download(bucket, obj, dest):
        calls.append((bucket, obj))
        shutil.copy(settings.model_path, dest)        # pretend this came from the bucket

    monkeypatch.setattr(model_store, "download_from_bucket", fake_download)
    monkeypatch.setattr(settings, "model_bucket", "test-bucket")
    monkeypatch.setattr(settings, "model_cache_path", tmp_path / "downloaded.onnx")
    model_store_info_cache_clear()

    with TestClient(app) as c:
        assert calls == [("test-bucket", "models/posture_model.onnx")]
        assert app.state.model_path == tmp_path / "downloaded.onnx"
        info = c.get("/model").json()
        assert info["source"] == "gs://test-bucket/models/posture_model.onnx"
        assert c.post("/predict", json={"features": [0.0] * 9}).json()["label"] == 0
    model_store_info_cache_clear()


def test_local_model_without_bucket(client):
    assert client.get("/model").json()["source"] == "local file"


def model_store_info_cache_clear():
    from app.routers.model_files import model_info
    model_info.cache_clear()

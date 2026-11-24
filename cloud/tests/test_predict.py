import json
from pathlib import Path
from fastapi.testclient import TestClient
from app.main import app

CASES = json.loads((Path(__file__).resolve().parents[2] / "models" / "onnx_test_cases.json").read_text())

def test_matches_python_reference():
    with TestClient(app) as client:          # "with" runs startup, so the model gets loaded
        r = client.post("/predict/batch", json={"samples": [c["input"] for c in CASES["cases"]]})
        assert r.status_code == 200
        for case, pred in zip(CASES["cases"], r.json()["predictions"]):
            p_good, p_bad = case["probabilities"]
            assert pred["label"] == (1 if p_bad > p_good else 0)
            assert abs(pred["confidence"] - max(p_good, p_bad)) < CASES["tolerance"]

def test_rejects_wrong_length():
    with TestClient(app) as client:
        assert client.post("/predict", json={"features": [0.0] * 8}).status_code == 422
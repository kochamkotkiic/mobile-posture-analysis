from .conftest import CASES


def test_matches_python_reference(client):
    r = client.post("/predict/batch", json={"samples": [c["input"] for c in CASES["cases"]]})
    assert r.status_code == 200
    assert r.json()["server_ms"] >= 0
    for case, pred in zip(CASES["cases"], r.json()["predictions"]):
        p_good, p_bad = case["probabilities"]
        assert pred["label"] == (1 if p_bad > p_good else 0)
        assert abs(pred["confidence"] - max(p_good, p_bad)) < CASES["tolerance"]


def test_single_predict(client):
    case = CASES["cases"][0]
    r = client.post("/predict", json={"features": case["input"]})
    assert r.status_code == 200
    assert r.json()["label"] == case["label"]


def test_rejects_wrong_length(client):
    assert client.post("/predict", json={"features": [0.0] * 8}).status_code == 422
    assert client.post("/predict/batch", json={"samples": [[0.0] * 8]}).status_code == 422


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_model_info_and_download(client):
    info = client.get("/model").json()
    assert len(info["version"]) == 64
    r = client.get("/model/file")
    assert r.status_code == 200
    assert len(r.content) == info["size_bytes"]
    assert r.headers["etag"] == info["version"]

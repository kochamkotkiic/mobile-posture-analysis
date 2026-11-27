from .conftest import A, CAL


def test_no_token_is_rejected(client):
    assert client.get("/me").status_code == 401
    assert client.get("/me", headers={"Authorization": "Bearer not-a-real-token"}).status_code == 401


def test_must_register_first(client):
    assert client.get("/me", headers=A).status_code == 404
    assert client.put("/me/calibration", headers=A, json=CAL).status_code == 404


def test_register_takes_email_from_token(client):
    r = client.put("/me", headers=A, json={"display_name": "Alice"})
    assert r.json() == {"uid": "alice", "email": "alice@test.com", "display_name": "Alice"}
    assert client.get("/me", headers=A).json()["display_name"] == "Alice"
    # calling again keeps the user, updates the name
    client.put("/me", headers=A, json={"display_name": "Ali"})
    assert client.get("/me", headers=A).json()["display_name"] == "Ali"


def test_calibration_roundtrip_latest_wins(client):
    client.put("/me", headers=A, json={})
    assert client.get("/me/calibration", headers=A).status_code == 404
    assert client.put("/me/calibration", headers=A, json=CAL).status_code == 200
    newer = {k: 0.2 for k in CAL}
    assert client.put("/me/calibration", headers=A, json=newer).status_code == 200
    assert client.get("/me/calibration", headers=A).json()["values"] == newer


def test_calibration_rejects_wrong_keys(client):
    client.put("/me", headers=A, json={})
    bad = dict(CAL); bad.pop("nose_z")
    assert client.put("/me/calibration", headers=A, json=bad).status_code == 422
    extra = dict(CAL, foo=1.0)
    assert client.put("/me/calibration", headers=A, json=extra).status_code == 422

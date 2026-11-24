from .conftest import A, B, C


def test_friend_request_flow(users, pushes):
    c = users
    r = c.post("/friends", headers=A, json={"email": "BOB@test.com"})   # email case doesn't matter
    assert r.status_code == 201
    fid = r.json()["id"]
    assert pushes[-1]["to"] == "fcm-bob" and pushes[-1]["data"]["type"] == "friend_request"

    assert c.get("/friends", headers=B).json()[0]["direction"] == "incoming"
    assert c.post(f"/friends/{fid}/accept", headers=A).status_code == 404   # requester can't accept
    assert c.post(f"/friends/{fid}/accept", headers=C).status_code == 404   # strangers can't either
    assert c.post(f"/friends/{fid}/accept", headers=B).json()["status"] == "accepted"
    assert pushes[-1]["to"] == "fcm-alice"


def test_friend_request_errors(users):
    c = users
    assert c.post("/friends", headers=A, json={"email": "nobody@test.com"}).status_code == 404
    assert c.post("/friends", headers=A, json={"email": "alice@test.com"}).status_code == 422
    c.post("/friends", headers=A, json={"email": "bob@test.com"})
    assert c.post("/friends", headers=A, json={"email": "bob@test.com"}).status_code == 409
    assert c.post("/friends", headers=B, json={"email": "alice@test.com"}).status_code == 409


def test_favorite_needs_acceptance(users):
    c = users
    fid = c.post("/friends", headers=A, json={"email": "bob@test.com"}).json()["id"]
    assert c.put(f"/friends/{fid}/favorite", headers=A, json={"favorite": True}).status_code == 409


def test_alert_notifies_favorite_only(friends_ab, pushes):
    c = friends_ab
    pushes.clear()
    r = c.post("/alerts", headers=A, json={"fired_at": "2026-11-24T15:30:00Z"})
    assert r.status_code == 201
    assert r.json()["notified"] == ["bob"]
    assert pushes == [{"to": "fcm-bob", "title": "Posture alert",
                       "data": {"type": "posture_alert", "user_uid": "alice", "alert_id": r.json()["id"]}}]

    # bob did NOT mark alice as favorite -> bob's alert notifies nobody
    pushes.clear()
    assert c.post("/alerts", headers=B, json={"fired_at": "2026-11-24T15:31:00Z"}).json()["notified"] == []
    assert pushes == []

    alerts = c.get("/alerts", headers=A).json()
    assert len(alerts) == 1 and alerts[0]["notified_count"] == 1


def test_unfavorite_and_unfriend(friends_ab, pushes):
    c = friends_ab
    fid = c.get("/friends", headers=A).json()[0]["id"]
    c.put(f"/friends/{fid}/favorite", headers=A, json={"favorite": False})
    assert c.post("/alerts", headers=A, json={"fired_at": "2026-11-24T15:30:00Z"}).json()["notified"] == []
    assert c.delete(f"/friends/{fid}", headers=C).status_code == 404
    assert c.delete(f"/friends/{fid}", headers=B).status_code == 204
    assert c.get("/friends", headers=A).json() == []

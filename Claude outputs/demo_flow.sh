#!/usr/bin/env bash
# End-to-end test of the PostureGuard cloud API against a RUNNING server, with dev tokens (no Firebase).
# Start the server first (in cloud/, venv active):
#   FIREBASE_ENABLED=false DEV_AUTH=true DATABASE_URL=sqlite:///./demo.db uvicorn app.main:app --port 8000
# Then, in another terminal:  bash demo_flow.sh   (or: bash demo_flow.sh http://other-host:8000)
set -u
URL="${1:-http://localhost:8000}"
ALICE="Authorization: Bearer dev:alice:alice@test.com"   # monitored user
BOB="Authorization: Bearer dev:bob:bob@test.com"         # her friend
J="Content-Type: application/json"
PASS=0; FAIL=0

check() {  # check "<description>" "<expected substring>" "<actual response>"
  if [[ "$3" == *"$2"* ]]; then echo "  OK    $1"; PASS=$((PASS+1))
  else echo "  FAIL  $1"; echo "        expected to contain: $2"; echo "        got: $3"; FAIL=$((FAIL+1)); fi
}
jget() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "== Server and model"
check "health"                 '"ok"'        "$(curl -s $URL/health)"
check "predict (calibrated pose -> good)" '"label":0' "$(curl -s -X POST $URL/predict -H "$J" -d '{"features":[0,0,0,0,0,0,0,0,0]}')"
check "predict rejects 8 values" '422' "$(curl -s -o /dev/null -w '%{http_code}' -X POST $URL/predict -H "$J" -d '{"features":[0,0,0,0,0,0,0,0]}')"
check "model info has version" '"version"' "$(curl -s $URL/model)"

echo "== Accounts"
check "no token -> 401"        '401'         "$(curl -s -o /dev/null -w '%{http_code}' $URL/me)"
check "register alice"         '"uid":"alice"' "$(curl -s -X PUT $URL/me -H "$ALICE" -H "$J" -d '{"display_name":"Alice"}')"
check "register bob"           '"uid":"bob"'   "$(curl -s -X PUT $URL/me -H "$BOB" -H "$J" -d '{"display_name":"Bob"}')"
curl -s -o /dev/null -X PUT $URL/me/fcm-token -H "$ALICE" -H "$J" -d '{"token":"fake-fcm-alice"}'
curl -s -o /dev/null -X PUT $URL/me/fcm-token -H "$BOB"   -H "$J" -d '{"token":"fake-fcm-bob"}'

echo "== Calibration"
CAL='{"nose_x":0.5,"nose_y":0.35,"nose_z":-0.6,"left_shoulder_x":0.77,"left_shoulder_y":0.72,"left_shoulder_z":-0.2,"right_shoulder_x":0.39,"right_shoulder_y":0.72,"right_shoulder_z":-0.2}'
check "save calibration"       '"nose_x":0.5' "$(curl -s -X PUT $URL/me/calibration -H "$ALICE" -H "$J" -d "$CAL")"
check "read it back"           '"right_shoulder_z":-0.2' "$(curl -s $URL/me/calibration -H "$ALICE")"
check "missing key -> 422"     '422' "$(curl -s -o /dev/null -w '%{http_code}' -X PUT $URL/me/calibration -H "$ALICE" -H "$J" -d '{"nose_x":0.5}')"

echo "== Friends"
FR=$(curl -s -X POST $URL/friends -H "$ALICE" -H "$J" -d '{"email":"bob@test.com"}')
check "alice sends request"    '"status":"pending"' "$FR"
FID=$(echo "$FR" | jget '["id"]')
check "bob accepts"            '"status":"accepted"' "$(curl -s -X POST $URL/friends/$FID/accept -H "$BOB")"
check "alice marks bob favorite" '"favorite":true' "$(curl -s -X PUT $URL/friends/$FID/favorite -H "$ALICE" -H "$J" -d '{"favorite":true}')"

echo "== Alert (10 min bad posture)"
check "alice's alert is stored" '"id"' "$(curl -s -X POST $URL/alerts -H "$ALICE" -H "$J" -d '{"fired_at":"2026-11-27T10:00:00Z"}')"
echo "        (push is skipped with FIREBASE_ENABLED=false; see the server log)"

echo "== Friend task: hold good posture 5 s"
TID=$(curl -s -X POST $URL/tasks -H "$BOB" -H "$J" -d '{"to_uid":"alice"}' | jget '["id"]')
check "bob created task $TID"  '"pending"' "$(curl -s $URL/tasks/$TID -H "$ALICE")"
GOOD=$(python3 -c "import json; print(json.dumps({'samples': [[0.0]*9]*20}))")   # 20 samples at the calibrated pose
check "alice holds good posture -> passed" '"status":"passed"' "$(curl -s -X POST $URL/tasks/$TID/result -H "$ALICE" -H "$J" -d "$GOOD")"
TID2=$(curl -s -X POST $URL/tasks -H "$BOB" -H "$J" -d '{"to_uid":"alice"}' | jget '["id"]')
FEW=$(python3 -c "import json; print(json.dumps({'samples': [[0.0]*9]*5}))")
check "too few samples -> failed" '"status":"failed"' "$(curl -s -X POST $URL/tasks/$TID2/result -H "$ALICE" -H "$J" -d "$FEW")"
check "bob sees results"       '"passed"' "$(curl -s "$URL/tasks?role=sent" -H "$BOB")"

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

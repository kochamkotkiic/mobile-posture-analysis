# PostureGuard cloud API

FastAPI server: posture classification (`/predict`), accounts (Firebase Auth), calibration sync,
alerts, friends, friend tasks (verified in the cloud) and push notifications (Firebase Cloud Messaging).
The same code runs on a laptop, on the PC as edge node (3-tier) and on Cloud Run. Everything is
configured with environment variables (`app/config.py`).

## Run locally

```bash
cd cloud
python3 -m venv .venv && source .venv/bin/activate
python -m pip install -r requirements.txt
python -m pytest -v                         # 21 tests, no Firebase needed

# server without Firebase, with dev tokens (for testing from the emulator):
FIREBASE_ENABLED=false DEV_AUTH=true uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload --reload-dir app
```
Interactive docs: http://localhost:8000/docs · from the Android Emulator the host is `http://10.0.2.2:8000`.

With real Firebase: `export GOOGLE_APPLICATION_CREDENTIALS=~/keys/<service-account>.json` and start
without the two flags. **Never commit the service-account file.**

## Authentication

1. The app signs in with the Firebase Auth Android SDK (email/password) and gets an ID token
   (`FirebaseAuth.getInstance().currentUser.getIdToken(false)`).
2. Every request except `/health`, `/predict*` and `/model*` sends `Authorization: Bearer <ID token>`.
3. After each sign-in the app calls `PUT /me` once (creates/updates the user), then `PUT /me/fcm-token`.

Dev mode (`DEV_AUTH=true`, local only): `Authorization: Bearer dev:<uid>:<email>`, e.g. `dev:emilia:emilia@test.com`.

## API

| Method & path | Body | Response / effect |
|---|---|---|
| `GET /health` | – | `{"status":"ok"}` |
| `POST /predict` | `{"features":[9 floats]}` | `{"label":0\|1,"confidence","server_ms"}` |
| `POST /predict/batch` | `{"samples":[[9 floats],…]}` (≤100) | `{"predictions":[{"label","confidence"}…],"server_ms"}` |
| `GET /model` | – | `{"name","version"(sha256),"size_bytes","url"}` |
| `GET /model/file` | – | the `.onnx` file (`ETag` = version) |
| `PUT /me` 🔒 | `{"display_name"?}` | `{"uid","email","display_name"}` (email from the token) |
| `GET /me` 🔒 | – | same |
| `PUT /me/fcm-token` 🔒 | `{"token"}` | stores where pushes go |
| `PUT /me/calibration` 🔒 | `{"nose_x":…, … ,"right_shoulder_z":…}` (exactly 9 keys) | saved calibration |
| `GET /me/calibration` 🔒 | – | latest calibration (`404` if none) |
| `POST /alerts` 🔒 | `{"fired_at":"2026-11-24T15:30:00Z"}` | stores the 10-min alert, pushes to **favorite** friends → `{"id","notified":[uid…]}` |
| `GET /alerts` 🔒 | – | my alerts, newest first |
| `POST /friends` 🔒 | `{"email"}` | friend request (push to them) |
| `GET /friends` 🔒 | – | `[{"id","friend":{uid,email,display_name},"status","direction","favorite"}]` |
| `POST /friends/{id}/accept` 🔒 | – | only the addressee can accept |
| `PUT /friends/{id}/favorite` 🔒 | `{"favorite":true}` | my alerts notify my favorites |
| `DELETE /friends/{id}` 🔒 | – | unfriend / decline / cancel |
| `POST /tasks` 🔒 | `{"to_uid"}` | friend sends "hold good posture 5 s" (push to target), expires after 10 min |
| `GET /tasks?role=received\|sent` 🔒 | – | tasks |
| `GET /tasks/{id}` 🔒 | – | one task |
| `POST /tasks/{id}/result` 🔒 | `{"samples":[[9 floats],…]}` normalized features from the 5 s | **cloud classifies**; passed if ≥15 samples and ≥80% good → push to the friend |

🔒 = needs the token. Errors: `401` no/invalid token, `404` not registered (`PUT /me` first) or not yours,
`409` conflict (already exists / not pending), `422` invalid body.

### Push notifications (FCM `data` payload, all values strings)
| `type` | to | extra keys |
|---|---|---|
| `friend_request` | addressee | `friendship_id` |
| `friend_accepted` | requester | `friendship_id` |
| `posture_alert` | favorite friends | `user_uid`, `alert_id` |
| `task` | challenged user | `task_id` |
| `task_result` | friend who sent it | `task_id`, `status` |

## Design notes (for the report / oral exam)
- **Images never reach the server**, only 9 numbers per sample (D2).
- The uid always comes from the verified token, never from the body: a user can't act as someone else.
- `/predict` is public on purpose: the on-device vs cloud latency measures network + model only.
  `server_ms` lets the phone split round trip into network time and model time.
- Decisions come from `probabilities`, never from the ONNX `label` output; onnxruntime 1.26/1.27 are
  avoided (wrong results for this model, PROGRESS.md P3). `tests/test_predict.py` proves server = Python = phone.
- Calibrations are appended, the newest wins (history kept). Alerts store phone time (`fired_at`) and
  server time (`received_at`).
- Friend task verified **in the cloud** (open question D3): the same ONNX model judges the uploaded samples.
- A failed push never fails the request (logged, `notified` shows who actually got it).

## Deploy (Cloud Run, EU) – once
1. **Database:** create a free Postgres on Neon or Supabase (EU region). Connection URL for this app:
   `postgresql+psycopg://USER:PASSWORD@HOST/DB?sslmode=require`
2. **Google Cloud** (console.cloud.google.com, the *same project* as Firebase): enable billing, then the
   APIs *Cloud Run* and *Artifact Registry*. Install the `gcloud` CLI, then:
   ```bash
   gcloud auth login
   gcloud config set project <PROJECT_ID>
   gcloud artifacts repositories create posture --repository-format=docker --location=europe-west8
   gcloud auth configure-docker europe-west8-docker.pkg.dev
   ```
3. **Build and push the image** (from the repo root; `linux/amd64` because Cloud Run doesn't run Apple-Silicon images):
   ```bash
   IMAGE=europe-west8-docker.pkg.dev/<PROJECT_ID>/posture/posture-api
   docker build --platform linux/amd64 -f cloud/Dockerfile -t $IMAGE .
   docker push $IMAGE
   ```
4. **Deploy:**
   ```bash
   gcloud run deploy posture-api --image $IMAGE --region europe-west8 --allow-unauthenticated \
     --set-env-vars "GOOGLE_CLOUD_PROJECT=<PROJECT_ID>,DATABASE_URL=postgresql+psycopg://…"
   ```
   `--allow-unauthenticated` only means Google doesn't block the requests; our own Firebase-token check
   still protects every 🔒 endpoint. Check with `curl https://<service-url>/health`.
   Token verification needs no extra permission. If pushes fail with a permission error, give the
   service's account the role *Firebase Cloud Messaging API Admin*.
5. Send the service URL to the team: it is the "server address" setting in the app (D8).

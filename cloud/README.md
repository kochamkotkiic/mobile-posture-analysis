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
python -m pytest -v                         # 26 tests, no Firebase needed

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

## Database and migrations

Tables are defined in `app/tables.py`. The database structure is changed **only through Alembic
migrations** (`migrations/versions/`): the server runs `upgrade head` itself at startup
(`MIGRATE_ON_STARTUP=true`, the default; in the cloud a separate job does it, see below), so a fresh database gets all tables and an existing one only the new changes,
keeping its data. The applied version is stored in the table `alembic_version`.

When you change a table:
```bash
alembic revision --autogenerate -m "what changed"     # writes migrations/versions/<rev>_<slug>.py
# read the generated file, then commit it together with the change to tables.py
```
`tests/test_migrations.py` fails if `tables.py` and the migrations disagree, so a forgotten migration
can't slip through. Useful: `alembic current` (version of the database), `alembic history`,
`alembic downgrade -1` (undo the last migration).

Integrity is enforced by the database, not only by the code: friendships have a UNIQUE `pair_key`
(both uids sorted), so two simultaneous requests can't create the same friendship twice
(`test_race_gives_409_not_duplicate`).

## Cloud architecture

```
Phone ──HTTPS──► Cloud Run "posture-api" (Milan, europe-west8)      autoscaling 0–5 instances,
                  │  container from Artifact Registry                Google spreads requests over them
                  ├─► Neon Postgres (Frankfurt)       users, calibrations, alerts, friends, tasks
                  ├─► Cloud Storage bucket            posture_model.onnx (downloaded at startup)
                  ├─► Firebase Auth                   verifies login tokens
                  └─► Firebase Cloud Messaging        push to the friend's phone
Cloud Run job "posture-migrate"                       applies database migrations once per deploy
```

- **Serverless + container:** Cloud Run runs our Docker image and starts/stops instances itself; with
  `--min-instances 0` it costs nothing while idle (the first request after a pause is slower: cold start).
- **Autoscaling:** each instance handles at most `--concurrency` requests at a time; when more arrive,
  Cloud Run starts more instances (up to `--max-instances`) and spreads the requests over them (its
  built-in load balancing). Every response carries `X-Instance-Id`, and `scripts/loadtest.py` shows how
  many instances answered and when each appeared.
- **Migrations as a separate job:** if every new instance ran migrations at startup, several instances
  starting together could run them at the same time. So the service runs with `MIGRATE_ON_STARTUP=false`
  and the job `posture-migrate` (same image, command `alembic upgrade head`) runs them once per deploy.
- **External cloud storage:** the model lives in a Cloud Storage bucket; the server downloads it at
  startup and serves the same file to phones (`/model`, `source` shows the bucket).

## Deploy (Cloud Run, EU)

Everything from the **repo root**, Docker Desktop running. Replace `<NEON_URL>` with the Neon connection
string (pooled), starting with `postgresql+psycopg://` and ending in `/neondb?sslmode=require`.

**Once: tools and project**
```bash
brew install --cask google-cloud-sdk
gcloud auth login
gcloud config set project mobilecloud-7dd8f
# link a billing account to the project in the console (Billing), then:
gcloud services enable run.googleapis.com artifactregistry.googleapis.com storage.googleapis.com
gcloud artifacts repositories create posture --repository-format=docker --location=europe-west8
gcloud auth configure-docker europe-west8-docker.pkg.dev
```

**Once: model bucket**
```bash
gcloud storage buckets create gs://mobilecloud-7dd8f-models --location=europe-west8 --uniform-bucket-level-access
cd cloud && GOOGLE_APPLICATION_CREDENTIALS="$PWD/secrets/<service-account>.json" \
  python scripts/upload_model.py --bucket mobilecloud-7dd8f-models && cd ..
```

**Every deploy: build, migrate, deploy**
```bash
IMAGE=europe-west8-docker.pkg.dev/mobilecloud-7dd8f/posture/posture-api
DB="<NEON_URL>"

docker build --platform linux/amd64 -f cloud/Dockerfile -t $IMAGE .
docker push $IMAGE

# 1) database migrations (first time: "create"; later: "update" with the same flags)
gcloud run jobs create posture-migrate --image $IMAGE --region europe-west8 \
  --command alembic --args upgrade,head --set-env-vars "DATABASE_URL=$DB"
gcloud run jobs execute posture-migrate --region europe-west8 --wait

# 2) the API
gcloud run deploy posture-api --image $IMAGE --region europe-west8 --allow-unauthenticated \
  --min-instances 0 --max-instances 5 --concurrency 10 --cpu 1 --memory 512Mi \
  --set-env-vars "GOOGLE_CLOUD_PROJECT=mobilecloud-7dd8f,MIGRATE_ON_STARTUP=false,MODEL_BUCKET=mobilecloud-7dd8f-models,DATABASE_URL=$DB"
```
`--allow-unauthenticated` only means Google lets requests reach the service; our Firebase-token check
still protects every 🔒 endpoint. No key file is needed in the cloud: the service runs as the project's
default service account, which can verify Firebase tokens.

**Check**
```bash
URL=$(gcloud run services describe posture-api --region europe-west8 --format 'value(status.url)')
curl $URL/health
curl $URL/model            # "source": "gs://mobilecloud-7dd8f-models/…"
```

**Permissions, if something is refused** (organizations sometimes don't give the default service
account its usual rights). Find it with
`gcloud run services describe posture-api --region europe-west8 --format 'value(spec.template.spec.serviceAccountName)'`
(empty = `<PROJECT_NUMBER>-compute@developer.gserviceaccount.com`), then grant what is missing:
```bash
SA=966260446085-compute@developer.gserviceaccount.com
gcloud storage buckets add-iam-policy-binding gs://mobilecloud-7dd8f-models --member serviceAccount:$SA --role roles/storage.objectViewer
gcloud projects add-iam-policy-binding mobilecloud-7dd8f --member serviceAccount:$SA --role roles/firebasecloudmessaging.admin
```
Logs: Cloud console → Cloud Run → posture-api → Logs.

## Load test and autoscaling (evaluation)

```bash
cd cloud
python scripts/loadtest.py --url $URL --concurrency 1  --duration 60 --csv results/c1.csv
python scripts/loadtest.py --url $URL --concurrency 50 --duration 60 --csv results/c50.csv
python scripts/loadtest.py --url $URL --concurrency 20 --duration 60 --batch 5 --csv results/batch5.csv
```
With `--concurrency 10` per instance, 50 simultaneous clients make Cloud Run start more instances: the
output lists each instance and when it first answered, plus latency mean/p50/p95/p99 and the network share
(round trip − `server_ms`). The Cloud Run "Metrics" tab shows the instance count over time (screenshot for
the report). Local reference (laptop, one instance): ~800 req/s, model 0.3 ms per request.

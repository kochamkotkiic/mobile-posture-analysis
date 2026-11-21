---
name: posture-mobile
description: Context, decisions and step-by-step plan for porting the posture_analysis app (Python + MediaPipe + RandomForest + Electron) to a native Android app in Kotlin with a cloud backend (2-tier first, optional 3-tier upgrade with the PC as edge node). Use whenever working on the mobile app, the Android port, the posture logic in Kotlin, exporting the model (ONNX), the cloud/edge /predict API, the on-device vs cloud comparison, the friend/multi-user feature, or when someone asks what the plan, team split or architecture is.
---

# Posture guard → mobile (Android, Kotlin) + cloud

Team project (3 people). This file is the shared source of truth for humans and coding agents.
**Keep the "Decisions" and "Status" sections up to date when something changes.**

## 0. Course requirements (from the professor's slides, `01_INTRODUCTION`)

- Project types: **2-tier** = Mobile ↔ Cloud (max **27** points), **3-tier** = Mobile ↔ Edge ↔ Cloud
  (max **31** points). In the 3-tier setup **our PC is the edge node**.
- Requirements: (1) mobile sensing (we use the **camera**), (2) process at least part of the data at the edge and
  **compare local/edge processing with cloud processing**, (3) cloud backend/API with data storage, (4) communication
  between the tiers, (5) **evaluation**: latency, execution time, bandwidth, energy or accuracy, with experimental
  results and discussion.
- Our reading of (2) for 2-tier: the phone itself is the "on-device" edge layer of the continuum, so comparing
  **on-device vs cloud** classification satisfies it. **To confirm with the professor** (see section 9).
- Final exam: oral defense of the code and architecture choices + paper presentation.

## 1. The product idea

- The phone stands **in landscape on the desk** (at the far end of the desk, leaning on the wall, raised on books
  so the lens is about chin height), front camera facing the user, plugged in to charge.
- The user does **not** use the phone while studying (a side benefit: fewer distractions).
- The app watches posture continuously. After **~3 s of bad posture** it shows a red **"Bad posture"** sign
  (no vibration/sound). After **10 minutes of continuous bad posture** it fires an **alert** (see D6).
- Multi-user idea (still open to change): when the 10-minute alert fires, a **favorite friend**
  is notified. The friend can tap a button to send a **task**: "hold good posture for 5 seconds". If the model
  confirms the user did it, the friend receives a confirmation.

### Proven so far
- Using the phone as a PC webcam (Windows Phone Link, "Use as a connected camera") with the **existing Python
  model**, the phone setup works well live.
- Recorded test `testp6` (phone, 85 s, good/bad/good/bad): accuracy (smoothed) 87.4%, sensitivity 100%,
  specificity 82.4%. That's in the same range as the laptop tests (≈76–99%). Most errors were the normal smoothing lag
  at transitions. Conclusion: **no retraining needed for now**; a longer test (5–10 min, mild slouching, natural
  movement, a second person) is still recommended.

## 2. Team split

| Person | Responsibility |
|---|---|
| **Emilia** | Change the architecture to mobile: Android app in Kotlin, camera → MediaPipe → features → classifier → 3 s "Bad posture" sign + 10-min alert. Minimal UI just for testing. Also: classifier mode switch (on-device / cloud, later edge) + latency logging for the evaluation. |
| **Zenon** | Mobile UI/UX and the multi-user (friend) feature. |
| **Nuno** | Cloud and authentication: store the model, `/predict` API, receive results, accounts, notifications. |
| *TBD* | 3-tier upgrade only: running the `/predict` server on the PC as edge + per-minute summaries edge → cloud (section 6.3). |

### Devices
- **Emilia: Samsung (Android)** → all real-device tests.
- **Zenon and Nuno: iPhones, no Mac** → they develop and test on the **Android Emulator** in Android Studio.
  The emulator can use the laptop webcam as its front camera (AVD settings → Camera Front: **Webcam0**), so the
  camera/pose pipeline also runs there.

## 3. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | **Native Android, Kotlin**, Jetpack Compose UI. **iOS is out of scope**: two of us have iPhones but nobody has a Mac (Xcode, needed to build for iPhone, is macOS-only). Kotlin Multiplatform / Flutter / React Native rejected: all need a Mac for iOS too, and the course is taught in Kotlin. | Decided |
| D2 | Pose detection runs **on the phone** (MediaPipe). Images/video are **never** sent to the cloud or edge; only the 9 landmark numbers. | Decided |
| D3 | Where the good/bad classification runs: **on-device by default** (`LocalOnnxClassifier`, works offline, ~1 ms). A **cloud mode** (`RemoteClassifier` → Nuno's `/predict`) is built too, switchable in the app, used for the **on-device vs cloud comparison** required by the course. Later the same `RemoteClassifier` can point at the PC (edge mode, D8). Friend's 5 s task verification: on-device or cloud still **open**. | Decided (friend part open) |
| D4 | Model format on the phone: **ONNX** (scaler + RandomForest exported together with `skl2onnx`), run with ONNX Runtime Android. The same `.onnx` file is what the cloud stores and serves. (LiteRT is for neural networks and can't run a scikit-learn RandomForest directly.) | Proposed |
| D5 | MVP scope for Emilia: calibration + monitoring + red "Bad posture" sign at 3 s + 10-minute alert, with a **minimal UI just for testing**. **Not now:** profile list, avatars/GIF, sessions/statistics, overlay, polished UI (Zenon). | Decided |
| D6 | **Two-level feedback.** (1) Bad posture ≥ 3 s → red "Bad posture" sign on screen only, **no vibration/sound**. (2) **10 min of continuous bad posture** → alert (MVP: simple on-screen alert dialog; later this event may also notify the friend). The alert fires **once per bad episode**; the 10-min timer resets as soon as posture is good again. | Decided |
| D7 | **Keep the ≈ 6 s delay** (≈ 2.8 s smoothing + 3 s timer), same as desktop. The smoothing filters short movements (reaching for a cup, looking at notes). Don't shrink the smoothing window. | Decided |
| D8 | **Build 2-tier first (phone ↔ cloud), keep 3-tier (phone ↔ PC edge ↔ cloud) as an optional upgrade.** Design rule: the server address is a **setting, never hard-coded**, and edge and cloud run the **same** `/predict` server code. | Decided |
| D9 | The friend feature lives **in the Android app**. Demo/testing: Emilia's Samsung = monitored user, an emulator on Zenon's or Nuno's laptop = friend. | Decided |

### Why not classify every frame in the cloud (D3 reasoning)
Classification happens 5 times per second. Doing it in the cloud means: no internet = no alerts, 50–300 ms network
lag per check, ~18 000 requests per user-hour. That's why on-device is the default. The cloud mode exists to
**measure** this difference for the report: send samples in batches (e.g. 5 samples once per second) or over a
WebSocket, not one HTTP request per frame.

Data that naturally goes to the cloud anyway: accounts/login, the user's calibration (sync to another phone),
"bad posture alert" events (needed to notify the friend), friend tasks and their results, the model file
(versioned), later session statistics.

## 4. The existing Python app (reference implementation)

| File | What it is |
|---|---|
| `src/core/server.py` | **The logic to port.** WebSocket server: OpenCV camera → MediaPipe → features → model → smoothing/timers → JPEG frames to the Electron UI. |
| `src/core/normalizer.py` | Feature normalization (subtract calibration). |
| `models/posture_model.pkl` | `RandomForestClassifier` (100 trees, max_depth 10, random_state 42), scikit-learn 1.3.2. Classes: 0 = good, 1 = bad. |
| `models/scaler.pkl` | `StandardScaler`, applied after normalization, before the model. |
| `data/posture_data.csv` | Training data (already normalized deltas; it does **not** contain raw coordinates or the calibration, so it can't be converted to different feature definitions). |
| `src/core/evaluate_external_test.py` | Runs every `data/test_frames/*.mp4` through the same logic as `server.py` (calibrates from the first 5 s) → `data/reports/<name>/wyniki_detekcji.csv`. |
| `src/core/add_ground_truth.py` | Adds ground-truth labels from time segments (`GROUND_TRUTH` dict) to those CSVs. |
| `src/training/*.ipynb` | Training, grid search, evaluation notebooks (stay in Python). |
| `ui/` + `main.py` | Electron UI + launcher (desktop only, not ported). |

Python version 3.11.9; `requirements.txt` has the library versions.
## 5. Exact logic spec (port 1:1 so the model behaves the same)

### 5.1 Features (order matters, it's the scaler/model input order)
```
[nose_x, nose_y, nose_z,
 left_shoulder_x, left_shoulder_y, left_shoulder_z,
 right_shoulder_x, right_shoulder_y, right_shoulder_z]
```
MediaPipe landmark indices: nose = 0, left_shoulder = 11, right_shoulder = 12.
Values are MediaPipe **normalized** landmarks (x, y in 0..1 of the image width/height; z relative depth).

### 5.2 Image preprocessing ⚠️
- In Python the frame is **mirrored horizontally before pose detection** (`cv2.flip(frame, 1)`, `server.py:106, 273`).
  The model was trained on mirrored frames → on Android **mirror the bitmap before passing it to MediaPipe**.
  If skipped, left/right shoulder swap and predictions are wrong.
- Rotate the frame upright first (CameraX `imageInfo.rotationDegrees`). Phone in **landscape**.
- Webcam frames were 4:3 (640×480). Request a **4:3** analysis resolution on Android (x/y are fractions of
  width/height, so the aspect ratio changes the scale of movements).

- Verified (Step 0.3, testp6): Tasks PoseLandmarker + mirroring = 88.27% smoothed accuracy (legacy 88.43%). 
Without mirroring, Tasks drops to 82.31%. The old test-video evaluation (evaluate_external_test.py) 
 did not mirror, unlike training and the live app.
### 5.3 Pose model settings
- Python uses legacy `mp.solutions.pose` with `model_complexity=1` → on Android use **`pose_landmarker_full.task`**
  (MediaPipe Tasks `PoseLandmarker`, `RunningMode.LIVE_STREAM`, one pose).
- Confidence: monitoring 0.6 (detection + tracking); calibration 0.7.
- Note: Tasks `PoseLandmarker` is a newer model than legacy `solutions.pose`. Values should be close but verify
  (plan step 0.3).
- Not MoveNet (listed in the slides): it has no z coordinate, and our model needs nose/shoulder z.

### 5.4 Calibration (`server.py:99–148`)
1. User sits straight, presses Start.
2. For **5.0 s**, collect a sample from each frame where `(visibility[11] + visibility[12]) / 2 > 0.6`.
3. If **≥ 10 samples**: calibration = mean of each of the 9 values. Otherwise: fail, ask to retry.
4. Store as `{ "nose_x": …, "nose_y": …, …, "right_shoulder_z": … }` (same keys as the Python profiles in
   `data/profiles/*.json` → `"calibration"`).

### 5.5 Per-frame pipeline (`server.py:266–354`)
```
features   = 9 values (5.1)
normalized = features[i] - calibration[key_i]
scaled     = (normalized - scaler.mean_) / scaler.scale_     # inside the ONNX pipeline
pred       = model.predict(scaled)          # 0 good, 1 bad
confidence = predict_proba(scaled)[pred]
```
- Run inference at most every **0.2 s (5 Hz)**; other frames reuse the last label.
- **Smoothing:** keep the last **20** predictions; smoothed label = bad if **≥ 14** (70%) of them are bad.
- **Bad timer:** when smoothed label becomes bad, start `badSince`; `badSeconds = now − badSince`.
  Smoothed label good → reset `badSince`.
- **Bad posture sign:** `badSeconds ≥ 3.0` → show red "Bad posture" sign until posture is good again.
  No vibration, no sound (D6).
- **10-minute alert:** `badSeconds ≥ 600` (i.e. 10 min of *continuous* smoothed-bad posture) → fire
  `LongBadPostureAlert` **once** for that episode (MVP: on-screen alert dialog). Smoothed label good → reset
  `badSince` and allow the alert again next episode.
- **No person detected:** reset the bad and good timers.
- Real-world delay ≈ 2.8 s (smoothing fills up) + 3 s threshold ≈ 6 s. Same as the desktop app; **kept on purpose** (D7).

⚠️ Difference from desktop: Python's 10-min `STRETCH_LIMIT` is **cumulative** (bad time adds up and only
resets after 2 min of good posture, `RECOVERY_LIMIT`). The mobile 10-min alert is **continuous** bad posture (D6).

Dropped for the MVP (exists in Python): cumulative `STRETCH_LIMIT` + GIF stretch alert, 2-minute
`RECOVERY_LIMIT`, session events/statistics saving, profile list, avatars.

## 6. Architecture

### 6.1 Tiers

```
2-TIER (build first)
  Phone (Kotlin) ─────────── internet ───────────► Cloud (Nuno)
  camera, pose, features,                          accounts, friend notifications,
  classify on-device (default)                     model file, /predict (cloud mode)
  or send 9 numbers to cloud /predict

3-TIER (optional upgrade, D8)
  Phone ─────── local Wi-Fi ───────► Edge = our PC ─────── internet ───────► Cloud
  same as above, plus                 same /predict server code,              + receives per-minute
  "edge mode" (server URL = PC)       per-minute summaries → cloud            summaries
```

Classifier modes (switch in the test UI): **On-device** / **Cloud** / later **Edge**. Same 9 numbers in,
same `Prediction` out, so the rest of the app doesn't care which one runs.

### 6.2 Android app

```
CameraX (front camera, landscape, 4:3, ImageAnalysis STRATEGY_KEEP_ONLY_LATEST)
  └─► rotate upright + mirror horizontally
       └─► PoseDetector (MediaPipe PoseLandmarker, full model, LIVE_STREAM)
            └─► FeatureExtractor (9 floats) ─► Normalizer (− calibration)
                 └─► PostureClassifier (interface), wrapped in TimedClassifier (logs latency)
                 │     ├─ LocalOnnxClassifier            (on-device, MVP, default)
                 │     └─ RemoteClassifier(baseUrl)      (cloud now; edge later = same class, PC's URL)
                 └─► PostureStateMachine (smoothing + 3 s timer)
                       └─► MonitorViewModel: StateFlow<PostureState> + event Flow
                             ├─► MonitorScreen (UI)
                             ├─► AlertManager (10-min alert dialog)
                             └─► later: friend feature (Zenon), cloud sync (Nuno)
```

Architecture pattern: **MVVM** (as in the course): Compose screens observe `StateFlow` via `collectAsState()`,
the ViewModel calls into the plain-Kotlin `posture/` logic. Inference runs on `Dispatchers.Default`, never on the
main thread. The Activity stays in the foreground with the screen on, so no background Service is needed for the MVP.

Packages:
- `camera/`: CameraX setup
- `pose/`: `PoseDetector` wrapper around MediaPipe
- `posture/`: `FeatureExtractor`, `Normalizer`, `PostureClassifier`, `LocalOnnxClassifier`, `PostureStateMachine`.
  **Plain Kotlin, no Android imports**, so it can be unit-tested on the JVM.
- `remote/`: `RemoteClassifier`, `TimedClassifier`, `ClassifierMode`, server URL setting
- `calibration/`: `CalibrationManager` (5.4), `CalibrationRepository` (DataStore)
- `alert/`: `AlertManager`
- `ui/`: `CalibrationScreen`, `MonitorScreen` (Compose)

### 6.3 3-tier upgrade (only after 2-tier works)
Extra work, estimated **~2–3 days across the team**, because the `/predict` server and `RemoteClassifier` already
exist from 2-tier:
1. Run the same `/predict` server on the PC (`uvicorn ... --host 0.0.0.0`).
2. Edge sends **per-minute summaries** to the cloud (seconds good/bad, number of alerts) instead of raw samples
   ("vertical aggregation" from the slides: less traffic to the cloud). ~1 day.
3. Network setup ~½ day:
   - Phone and PC on the **same network**. University Wi-Fi often blocks device-to-device traffic → use home
     Wi-Fi or the phone's hotspot.
   - Android 9+ blocks plain `http://` → add a `network_security_config.xml` allowing cleartext to the PC's IP.
   - In the Android Emulator the host PC is **`10.0.2.2`**.
4. One more column in the evaluation table. ~½–1 day.

### Contracts between team members (agree early, change only together)
```kotlin
// Emilia ↔ Nuno: classification can be local or remote
interface PostureClassifier {
    suspend fun classify(normalizedFeatures: FloatArray): Prediction   // 9 values, order 5.1
}
data class Prediction(val label: Int, val confidence: Float)          // 0 good, 1 bad

enum class ClassifierMode { ON_DEVICE, CLOUD, EDGE }                   // EDGE used only after 3-tier upgrade

// Emilia → Zenon (and Nuno): what the rest of the app listens to
data class PostureState(
    val personVisible: Boolean,
    val smoothedLabel: Int,       // 0 good, 1 bad
    val confidence: Float,
    val badSeconds: Float,
    val badPostureShown: Boolean,  // badSeconds ≥ 3 s → red "Bad posture" sign
)
sealed interface PostureEvent {
    data object LongBadPostureAlert : PostureEvent  // 10 min continuous bad, once per episode → later "notify friend"
    data object PostureRecovered : PostureEvent
}
```
- The friend's "5 s good posture" task is just another consumer of `PostureState` (and of raw normalized
  samples if the cloud verifies it, D3).
- Calibration JSON format: section 5.4 (same keys as the Python profiles).
- `/predict` API (same on cloud and edge, D8):
  `POST /predict {"features": [9 floats]} → {"label": 0|1, "confidence": float}`;
  batch variant `{"samples": [[9 floats], …]} → {"predictions": [{"label", "confidence"}, …]}`.
  Implementation: FastAPI + `onnxruntime` loading the same `posture_model.onnx`.
  The task verification endpoint receives the samples recorded during the 5 s task and returns pass/fail.
- Edge → cloud summary (3-tier only), proposal:
  `POST /summary {"userId", "minuteStart", "goodSeconds", "badSeconds", "alerts"}`.

## 7. Emilia's step-by-step plan

**Order matters: do the risky steps (0.3 and 2) first.**

### Step 0: Python prep (in this repo) · ½–1 day
1. Export `scaler.pkl` + `posture_model.pkl` as one `Pipeline` to **`posture_model.onnx`** with `skl2onnx`
   (a version compatible with scikit-learn 1.3.2; input `float32 [None, 9]`). Set `options={'zipmap': False}`
   so probabilities come out as a plain array.
2. **Equality check:** run rows of `data/posture_data.csv` through the `.pkl` and the `.onnx` (with `onnxruntime`)
   and assert labels are equal and probabilities match (≈1e-5). Save ~20 `{input, label, proba}` cases as JSON → used by Kotlin unit tests.
3. **Pose model check:** copy `evaluate_external_test.py`, replace `mp.solutions.pose` with the Tasks
   `PoseLandmarker` (`pose_landmarker_full.task`, VIDEO mode), run on `testp6.mp4`, compare metrics with the
   current ones (section 1). Close → continue. Much worse → solve before Android work (maybe retrain on Tasks landmarks).

### Step 1: Android project · ½ day
- Android Studio → Kotlin + Jetpack Compose, **minSdk 24** (26 also fine).
- Dependencies: `com.google.mediapipe:tasks-vision`, `androidx.camera:camera-core`, `camera-camera2`,
  `camera-lifecycle`, `camera-view`, `com.microsoft.onnxruntime:onnxruntime-android`, `androidx.datastore`.
  Use current stable versions.
- **Shortcut:** start from Google's official sample `google-ai-edge/mediapipe-samples` →
  `examples/pose_landmarker/android` (CameraX + PoseLandmarker already working).
- `assets/`: `pose_landmarker_full.task`, `posture_model.onnx`.
- Add an `AGENTS.md` to the Android project (structure, MVVM, coding rules), as in the course's agentic-coding slides.

### Step 2: Camera + pose · 1–2 days
- Front camera, `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`, 4:3.
- Rotate upright → **mirror** → `PoseLandmarker.detectAsync(...)` (confidence 0.6).
- Activity locked to landscape (`android:screenOrientation="sensorLandscape"`), `FLAG_KEEP_SCREEN_ON`,
  camera permission flow.
- **Checkpoint:** log nose/shoulder x,y,z; compare roughly with Python values for the same pose
  (left shoulder must be on the same side as in Python).

### Step 3: Logic in plain Kotlin + unit tests · 1–2 days
- `FeatureExtractor`, `Normalizer`, `LocalOnnxClassifier` → tests with the JSON cases from step 0.2.
- `PostureStateMachine` (section 5.5) with an injectable clock → tests such as: bad 2.9 s → no sign; bad 3.1 s →
  sign; 13/20 bad → still good; 14/20 bad → bad; person lost → timers reset; bad 599 s → no alert; bad 600 s →
  exactly one `LongBadPostureAlert`; stays bad 20 min → still only one; good then bad again 10 min → second alert.

### Step 4: Calibration · 1 day
- Screen: preview + "Sit straight, then press Start" + 5 s progress bar; rules from 5.4; save to DataStore.
- App start: calibration exists → monitoring; "Recalibrate" button. **Recalibrate whenever the phone is moved.**

### Step 5: Monitoring + alert · 1 day
- **Minimal testing UI only** (polished UI is Zenon's): preview, status text (green "Good" / red "Bad posture"
  sign after 3 s), bad-seconds counter, Recalibrate button.
- `AlertManager`: on `LongBadPostureAlert` show a simple alert dialog. No vibration/sound (D6).
- Tip for testing: make the 10-min limit a constant that can be lowered (e.g. 30 s) in a debug build.

### Step 6: Real test · 1 day
- Phone at the real position. Repeat the `testp6` scenario (good/bad/good/bad, include mild slouching) and compare.
- 1-hour battery/heat check while charging.

### Step 7: Hand-off
- Document `PostureState`/`PostureEvent` for Zenon and `PostureClassifier` + calibration JSON for Nuno.

### Step 8: Cloud mode (2-tier) · 1 day, when Nuno's `/predict` exists
- `RemoteClassifier(baseUrl)` using the batch API (5 samples once per second), server URL as a setting.
- `TimedClassifier` wrapper: log per-classification latency (send → answer) to a CSV/log file.
- Test-UI switch **On-device / Cloud**.

### Step 9: Evaluation · 1 day
- Same test scenario in each mode; measure latency (mean, p95), behaviour without internet, data sent per minute,
  battery over e.g. 30 min. Table: mode × metric. Discuss with the edge/cloud formulas from the intro slides.

### Step 10 (optional): 3-tier upgrade
- Section 6.3. On the phone only: add `EDGE` to the switch (same `RemoteClassifier`, PC's URL) + cleartext config.

**Estimate:** ~1.5–2 weeks to working on-device MVP, + ~2 days for cloud mode and evaluation.

## 8. Physical setup / testing tips
- Phone upright in landscape (a cheap stand is better than leaning on the wall: reproducible angle), lens ≈ chin
  height, user centered with shoulders clearly visible, **no window behind the user** (backlight hurts detection).
- Distance (~80–110 cm at the end of the desk) makes movements look smaller than on the laptop (~50–70 cm).
  Calibration removes position, **not scale**. If mild slouching is missed, move the phone closer or higher. A
  long-term fix is scale-invariant features (divide by shoulder width from calibration), but that needs **new**
  training data (see `posture_data.csv` note in section 4).
- Test videos: put an `.mp4` in `data/test_frames/`, run `python src/core/evaluate_external_test.py`, add segments
  to `GROUND_TRUTH` in `src/core/add_ground_truth.py`, run it. First ≥ 5 s of the video must be good posture
  (used for calibration). Ignore `FA/h` on short videos (extrapolated to one hour → huge numbers).
- Emulator (Zenon, Nuno): AVD → Camera Front: **Webcam0** to use the laptop webcam; host PC = `10.0.2.2`.

## 9. Open questions
- D3 (friend part): is the friend's 5 s task verified on the phone or in the cloud?
- 3-tier upgrade: who owns the edge part (running the server on the PC + summaries to the cloud)?

## 10. Status (update me)
- [x] Phone camera validated with the Python model (live + `testp6`)
- [x] Platform decided: native Android only, teammates use the emulator (D1, D9)
- [x] Architecture decided: 2-tier first, 3-tier optional (D8)
- [x] Step 0: ONNX export + equality check + PoseLandmarker comparison
- [ ] Step 1: Android project
- [ ] Step 2: Camera + pose
- [ ] Step 3: Logic + unit tests
- [ ] Step 4: Calibration
- [ ] Step 5: Monitoring + alert
- [ ] Step 6: Real test
- [ ] Step 7: Hand-off to Zenon / Nuno
- [ ] Step 8: Cloud mode (needs Nuno's `/predict`)
- [ ] Step 9: Evaluation (on-device vs cloud)
- [ ] Step 10 (optional): 3-tier upgrade

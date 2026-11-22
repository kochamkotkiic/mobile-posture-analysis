# PostureGuard Mobile: Progress Report

Running log of the port of the desktop posture app (Python + MediaPipe + RandomForest + Electron) to an
Android app. Written by Emilia (camera → pose → classifier part).

How to keep it up to date: each finished step gets a subsection in **section 3**, every real problem (one a teammate could also run into) a row in
**section 4**, every number a row in **section 5**, every new file a row in **section 6**.

Plan, decisions and team split: [`.claude/skills/posture-mobile/SKILL.md`](../.claude/skills/posture-mobile/SKILL.md).
Rules for coding agents in the Android project: [`android/AGENTS.md`](../android/AGENTS.md).

| Step | What | Status | Date |
|---|---|---|---|
| 0 | Python prep: ONNX export, equality check, new pose model check | ✅ done | 2026-11-21 |
| 1 | Android project + libraries + model files + self-test | ✅ done | 2026-11-21 |
| 2 | Camera + pose detection | ✅ done | 2026-11-21 |
| 3 | Posture logic in plain Kotlin + unit tests | ✅ done | 2026-11-21 |
| 4 | Calibration | ✅ done | 2026-11-22 |
| 5 | Monitoring screen + alerts | ✅ done | 2026-11-22 |
| 6 | Real test (accuracy, battery, heat) | | |
| 7 | Hand-off to Zenon / Nuno | | |
| 8 | Cloud mode (2-tier) | | |
| 9 | Evaluation: on-device vs cloud | | |
| 10 | Optional: 3-tier upgrade (PC as edge) | | |

---

## 1. Architecture decisions (summary)

Full reasoning in SKILL.md, section 3.

- **Native Android, Kotlin + Jetpack Compose (D1).** The two teammates have iPhones and nobody has a Mac,
  so iOS (which needs Xcode on macOS) is out of scope. Teammates develop on the Android Emulator (it can use the
  laptop webcam as the front camera); real-device tests run on Emilia's Samsung.
- **Images never leave the phone (D2).** Only the 9 landmark numbers may be sent to a server.
- **Classification on the phone by default; a switchable cloud mode for the comparison the course requires (D3).**
- **2-tier first (phone ↔ cloud), 3-tier (phone ↔ PC edge ↔ cloud) as an optional upgrade (D8).**
  The server address is a setting, and edge and cloud run the same `/predict` server code.
- **Friend feature stays in the Android app (D9).** Demo: Samsung = user, emulator = friend.
- **Model format: ONNX (D4).** One file used by the phone, the cloud and the edge.
  ONNX Runtime **1.25.1** on Android; the decision is taken from the probabilities (see P4).

---

## 2. Course requirements we must cover

From the professor's introduction slides (`01_INTRODUCTION`):

| Requirement | How we cover it |
|---|---|
| Mobile sensing | Front camera |
| Edge processing + compare local/edge with cloud | Pose + classification on the phone; cloud `/predict` mode; latency comparison |
| Cloud service with storage | Nuno: accounts, model file, events, friend notifications |
| Communication between tiers | Phone ↔ cloud (and PC edge in 3-tier) |
| Evaluation (latency, bandwidth, energy, accuracy) | Section 5 of this report collects the numbers |

Grading: 2-tier max 27 points, 3-tier max 31. Open question for the professor: does on-device vs cloud
count as the "edge" comparison in a 2-tier project?

---

## 3. Work log

### Step 0: Python preparation (2026-11-21)

**Goal:** before building the app, prove that both "brains" survive the move to Android:
the *eyes* (MediaPipe: image → 9 numbers) and the *judge* (scaler + RandomForest: 9 numbers → good/bad).

#### 0.1 Export the model to ONNX
- `src/export/export_onnx.py` combines `models/scaler.pkl` + `models/posture_model.pkl` into one sklearn
  `Pipeline` and converts it with `skl2onnx` to **`models/posture_model.onnx`** (333 KB).
- Input `features`: float32 [N, 9], already normalized (features − calibration).
- Outputs `label` (0 = good, 1 = bad) and `probabilities` [P(good), P(bad)] (`zipmap=False` → plain array).
- The scaler is **inside** the ONNX file, so the app must not scale again.
- Why ONNX: Kotlin cannot read `.pkl`; ONNX runs on Android, in the cloud and on a PC.

#### 0.2 Check the ONNX file gives the same answers as the `.pkl` files
- `src/export/check_onnx.py` runs all 3058 rows of `data/posture_data.csv` through both.
- **Result: PASS.** 0 label differences, max probability difference 6.9e-07 (limit 1e-05).
- It also saves **`models/onnx_test_cases.json`**: 20 rows (6 borderline near 50/50, plus random good and bad
  rows, 10 good + 10 bad in total, fixed random seed 42) with the Python answers. Used by the Android self-test
  and later by the unit tests.

#### 0.3 Check the new pose model (the one Android must use)
- Android cannot use the legacy `mp.solutions.pose`; it uses MediaPipe Tasks `PoseLandmarker`
  (`models/pose_landmarker_full.task`). The model was trained on the legacy one.
- `src/export/compare_pose_models.py` runs `testp6.mp4` (phone video, 85 s) through the same pipeline as
  `server.py`, with both pose models, with and without mirroring, and scores it with the same metric code as
  `batch_metryki_test.py`. Ground truth is taken from the existing `data/reports/testp6/wyniki_detekcji.csv`.
- **Final results, on upright frames** (`data/reports/testp6/pose_comparison_upright.csv`), i.e. how the app
  sees the user:

| Variant (upright frames) | Acc. smooth | Sensitivity | Specificity | FA/h |
|---|---|---|---|---|
| Legacy, no mirror | 88.27% | 89.72% | 87.70% | 127.1 |
| Legacy, mirrored | 89.45% | 93.19% | 87.98% | 84.7 |
| Tasks, no mirror | 88.51% | 93.19% | 86.67% | 84.7 |
| **Tasks, mirrored (Android)** | **89.69%** | **94.03%** | **87.98%** | **84.7** |

- **Conclusion: no retraining needed.** The new pose model with mirroring is as good as the old one (slightly
  better). Mirroring helps by about 1 point.
- Landmark positions old vs new on the same frames: x/y move almost identically (correlation 0.98–0.997);
  z differs more (correlation 0.86–0.91), but calibration removes the constant offset.
- Reference values for Android (`landmarks_tasks_mirror-yes_upright.csv`): nose above the shoulders
  (mean y 0.35 vs 0.72). MediaPipe's `left_shoulder` is on the right side of the image (x 0.77 vs 0.39), but
  this is true **with and without** mirroring (100% of frames in both runs): MediaPipe names the shoulder on the
  image's right "left", as if looking at a photo. So left/right positions cannot show whether an image was
  mirrored; mirroring changes which real shoulder gets which name and flips all x values (x → 1 − x).
- History: the first run of this comparison (`pose_comparison.csv`, `landmarks_*_mirror-yes.csv`) was done on
  **upside-down** frames, found during Step 2 (problem P5). Those numbers are kept only for reference:

| Variant (upside-down frames, superseded) | Acc. smooth | Sensitivity | Specificity | FA/h |
|---|---|---|---|---|
| Legacy, no mirror (= old `testp6` evaluation) | 87.37% | 100% | 82.40% | 169.5 |
| Legacy, mirrored | 88.43% | 99.17% | 84.21% | 254.2 |
| Tasks, no mirror | 82.31% | 100% | 75.36% | 296.5 |
| Tasks, mirrored | 88.27% | 100% | 83.66% | 127.1 |

  The first row reproduces the earlier `testp6` result exactly, which showed the setup matched the old one.

### Step 1: Android project (2026-11-21)

**Goal:** an Android project with all libraries and model files, and a proof that both models work on the phone.

- Installed Android Studio; created the project with the wizard: *Empty Activity* (Compose), name
  `PostureGuard`, package `com.postureguard`, min SDK 26, Kotlin DSL, in `android/`.
  Generated with Android Gradle Plugin 9.5.2, Kotlin 2.2.10, compile/target SDK 37.
- Libraries (`android/gradle/libs.versions.toml`):

  | Library | Purpose | Version |
  |---|---|---|
  | CameraX (core, camera2, lifecycle, view) | front camera | 1.6.2 |
  | MediaPipe `tasks-vision` | pose detection | 0.10.33 |
  | ONNX Runtime Android | posture model | **1.25.1** (see P4) |
  | DataStore Preferences | save calibration | 1.3.0 |

- `.task` and `.onnx` files are stored uncompressed in the APK (`androidResources.noCompress`).
- Model files copied to `android/app/src/main/assets/`: `pose_landmarker_full.task`, `posture_model.onnx`,
  `onnx_test_cases.json`. **These are copies; `models/` is the source.** After re-exporting, copy again.
- Manifest: camera permission, `sensorLandscape` orientation. `MainActivity` keeps the screen on.
- `android/AGENTS.md`: project rules for coding agents (mirroring, feature order, no double scaling, decide from
  probabilities, ONNX Runtime version).
- **Self-test screen** (`selftest/SelfTest.kt`, `selftest/SelfTestScreen.kt`, temporary). Checks run on a
  background thread (`Dispatchers.Default`):
  1. MediaPipe loads `pose_landmarker_full.task` → ✅ loaded in ~850–935 ms
  2. ONNX Runtime loads `posture_model.onnx` with input `features` and outputs `label`, `probabilities` → ✅
  3. ONNX on the phone gives the same answers as Python on the 20 test cases → ✅ 20/20 decisions match,
     max probability difference 6.6e-07, **0.11 ms per classification** (Samsung)
- First run failed check 3 (10/20 wrong) → problem P4, fixed.
- The app takes its decision from the probabilities: bad if P(bad) > P(good) (same rule as sklearn), not from
  the `label` output.

### Step 2: Camera + pose (2026-11-21)

**Goal:** the app sees the user: front camera → rotate upright → mirror → MediaPipe PoseLandmarker.

- `camera/CameraConfig.kt`: CameraX Preview + ImageAnalysis, both **4:3 around 640×480** (like the training
  webcam), `STRATEGY_KEEP_ONLY_LATEST`, RGBA output.
- `camera/FrameAnalyzer.kt`: on the analysis thread: `ImageProxy` → Bitmap → **rotate upright → mirror** → detector.
- `pose/PoseTypes.kt` (plain Kotlin): `Landmark`, `PoseFrame` (33 landmarks, nose = 0, shoulders = 11/12).
- `pose/PoseDetector.kt`: MediaPipe `PoseLandmarker`, LIVE_STREAM, one pose, confidence 0.6; measures the
  detection time per frame.
- `ui/posedebug/PoseDebugViewModel.kt` (MVVM): owns the detector and the camera thread, exposes
  `StateFlow<PoseDebugState>`, computes pose FPS and timings, logs one line per second (Logcat tag `PoseDebug`).
- `ui/posedebug/PoseDebugScreen.kt`: camera permission, preview with dots on nose/shoulders, live values,
  automatic checks (nose above shoulders; shoulders level), a mirror test instruction, keeps the frame rotation
  correct when the phone is turned 180°.
- `MainActivity` now shows the pose debug screen (self-test code kept in `selftest/`).
- While preparing the reference values, found that `testp6` had been evaluated upside down → problem P5;
  Step 0.3 re-measured on upright frames.
- **Result on the Samsung:**
  - how each property of the frame was checked:

    | Property | How it was checked | Result |
    |---|---|---|
    | 640×480, 4:3 landscape | Size of the image MediaPipe actually received (from its result) | ✅ |
    | Upright | Nose above shoulders (y 0.47 < 0.83–0.85); shoulders level (y 0.83 vs 0.85, so not rotated 90°); still correct after turning the phone 180° | ✅ |
    | Mirrored | Code flips the frame after rotating (`FrameAnalyzer.kt`); the nose dot sits on the nose although the face is off-centre (x 0.575) in the mirrored selfie preview; movement test: leaning to your own left must make nose x smaller | ✅ (leaning to the left: nose x got smaller, like a mirror) |

  - the first version of the screen also had a check "`left_shoulder` x > `right_shoulder` x"; it was removed
    because it passes with and without mirroring (see Step 0.3), so it did not prove mirroring
  - visibility 0.99–1.00
  - **~23 pose FPS, ~67 ms pose detection, ~5.6 ms rotate + mirror**
  - phone turned 180° (upside-down landscape): dots still follow ✅
- Note for Step 6: in this test the shoulder width was 0.53 of the image width vs 0.37 in `testp6`, i.e. the user
  sat much closer. Calibration removes position, not scale, so the real test must use the real phone position.
- Not proven yet: that the whole chain (calibration → classification → smoothing) recognises posture on the
  phone as well as on the PC. Different camera hardware, distance and angle change the size of movements;
  checked end-to-end in Step 6.

### Step 3: Posture logic in plain Kotlin + unit tests (2026-11-21)

**Goal:** turn the 9 numbers per frame into a decision, exactly like the per-frame loop of `server.py`
(lines 266–354), and prove it with tests that run on a normal computer.

New package `posture/` (plain Kotlin, no Android imports, except ONNX Runtime in `LocalOnnxClassifier`):

| File | What it does | Python original |
|---|---|---|
| `PostureContracts.kt` | Team contracts from SKILL.md: `PostureClassifier`, `Prediction` (+ decision from probabilities), `PostureState`, `PostureEvent` | – |
| `FeatureExtractor.kt` | 33 landmarks → 9 features (nose, left shoulder, right shoulder; x, y, z), feature names = profile keys, shoulder visibility | `extract_features()` |
| `Calibration.kt` | 9 calibration values, `normalize()` = features − calibration, map with the Python keys (missing key = 0) | `normalize_features()` |
| `InferenceThrottle.kt` | Classify at most every 200 ms (5 Hz) | `INFERENCE_INTERVAL` |
| `PostureStateMachine.kt` | Last 20 predictions, bad if ≥ 14; bad timer; sign at 3 s; `LongBadPostureAlert` once after 10 min continuous; `PostureRecovered`; person lost → timers reset, predictions kept | main loop |
| `LocalOnnxClassifier.kt` | Runs `posture_model.onnx` on one sample, decision from probabilities | `model.predict_proba()` |

Behaviour copied from `server.py`, which matters for the tests:
- pose detection on every frame, classification at most every 0.2 s, the last label is kept in between;
- the timers are updated on every frame;
- when no person is visible the timers reset, but the last 20 predictions are **kept**.

The state machine gets the time as a parameter instead of reading the clock, so tests can simulate 20 minutes
in milliseconds; the app passes the real time.

**Tests**
- JVM unit tests (`app/src/test/.../posture/`, run on the PC): 27 tests in `PostureStateMachineTest`,
  `FeatureAndCalibrationTest`, `PredictionAndThrottleTest`, incl. the SKILL.md scenarios: 13/20 bad → good,
  14/20 → bad; bad 2.9 s → no sign, 3.1 s → sign; the sign appears 5.8 s after slouching starts (2.8 s smoothing
  + 3 s); person lost resets timers but keeps predictions; 599 s → no alert, 600 s → exactly one; 20 min → still
  one; good break → second alert possible; alert needs continuous bad posture.
- Checked that the tests can fail: with 13 instead of 14 votes, 8 tests fail; if predictions are cleared when
  the person is lost, the matching test fails.
- Instrumented test on the phone (`app/src/androidTest/.../posture/LocalOnnxClassifierTest.kt`): same decision
  and confidence as Python on the 20 cases of `onnx_test_cases.json`.
- **Result:** all 27 JVM tests pass in Android Studio; `LocalOnnxClassifierTest` passes on the phone
  (`connectedDebugAndroidTest` on the Samsung SM-A566B, Android 17: BUILD SUCCESSFUL).

### Step 4: Calibration (2026-11-22)

**Goal:** record the user's own "sitting straight" position. The model judges the difference from it
(features − calibration), so nothing can be classified without a calibration.

Rules taken from the desktop app (`server.py` lines 99–148, `calibration.py`), basics only:

| Rule | Value |
|---|---|
| Pose detection confidence during calibration | 0.7 (monitoring uses 0.6) |
| A frame is used only if the mean shoulder visibility is | > 0.6 |
| Start possible only when | shoulder visibility > 0.6 |
| Collection time after Start | 5 s |
| Minimum frames | 10, otherwise "not enough frames, try again" |
| Calibration | mean of each of the 9 features |
| Detection hint | "excellent" if visibility > 0.7, otherwise "adjust your position" |
| Stored keys | `nose_x` … `right_shoulder_z` (same as the Python profiles) |

Left out on purpose (D5): profiles/names, avatars, choosing a profile; there is one calibration per phone.

Added for the phone: a **3 s countdown** after pressing Start (frames during it are ignored). On the laptop the
user was already in position at the keyboard; with the phone at the far end of the desk the user has to touch it
and then sit back, otherwise the calibration would record them leaning towards the phone.

- `calibration/CalibrationCollector.kt` (plain Kotlin): the 5 s collection and averaging; gets the frame time
  as a parameter, unit-tested on the PC.
- `calibration/CalibrationRepository.kt`: saves/loads the calibration with DataStore (9 values + time + number
  of frames); the old calibration stays until a new one is saved. Stored in the app's private storage:
  `/data/data/com.postureguard/files/datastore/calibration.preferences_pb` (not visible in the phone's file
  manager; viewable in Android Studio → Device Explorer; deleted on uninstall or "Clear storage").
- `ui/common/CameraPreview.kt`: the Step 2 camera preview + dots moved into a shared file (reused in Step 5).
- `ui/calibration/CalibrationViewModel.kt` + `CalibrationScreen.kt` (MVVM): states Ready → Collecting →
  Calibrated; Start sets a flag and the next camera frame starts the 5 s (same clock as the frames, collector used
  from one thread only); after calibrating, a live check shows "current features − calibration".
- `MainActivity` now opens the calibration screen.
- **Tests:** `CalibrationCollectorTest` (12 JVM tests): mean of the frames; frames without a person or with
  visibility ≤ 0.6 ignored; 9 frames → fail, 10 → ok; frame at exactly 5 s still used; Start again discards old
  samples; cancel; countdown 3-2-1, frames during the countdown ignored, 5 s collected after it. A visibility rule
  changed from `>` to `>=` is caught.
- **Result:** the first 9 tests pass in Android Studio and calibration works on the Samsung; countdown added
  afterwards (to re-test).

### Step 5: Monitoring screen + alerts (2026-11-22)

**Goal:** connect everything into live monitoring, the actual app:
camera → pose (confidence 0.6) → 9 features − calibration → model (at most 5×/s) → smoothing + timers →
green "Good" / red "Bad posture" after 3 s → alert after 10 min of continuous bad posture.

Rules from the desktop app (basics only, SKILL.md step 5): pose confidence 0.6 while monitoring; classification
at most every 0.2 s; 14 of the last 20 bad → bad; red sign after 3 s; 10 min continuous → a simple on-screen alert,
no sound or vibration (D6). Left out (D5): stretch GIF, statistics/sessions, avatars.

- `posture/PosturePipeline.kt` (plain Kotlin): the whole per-frame chain (features → − calibration → classifier
  via `InferenceThrottle` → `PostureStateMachine`); new calibration or settings → start from scratch; measures the
  time of each model run. The classifier is an interface, so the cloud classifier (Step 8) plugs in here.
- `alert/AlertManager.kt`: shows the alert on `LongBadPostureAlert` until the user presses OK.
- `ui/common/CameraPermission.kt`: shared camera permission check (used by both screens).
- `ui/monitor/MonitorViewModel.kt`: pose detector (0.6), ONNX model loaded on a background thread, one queue
  (`Channel`) for pose results, calibration changes and button presses, processed by one background coroutine,
  so the pipeline is never used from two threads; exposes `postureState` and `events` for Zenon/Nuno;
  logs once per second (Logcat tag `Monitor`).
- `ui/monitor/MonitorScreen.kt`: preview + dots, big status box (green "Good" / red "Bad posture" / grey "No one
  detected"), bad-posture seconds, last model answer, **test mode** switch (alert after 30 s instead of 10 min),
  Recalibrate button, live pose FPS / model runs per second / model time; the alert dialog.
- `MainActivity`: navigation: no calibration → calibration screen; calibration saved → monitoring;
  "Start monitoring" / "Recalibrate" switch between them.
- **Result on the Samsung:** all 48 JVM tests pass in Android Studio. With the phone standing in place and the user
  not touching it: sitting straight → green "Good"; mild and strong slouching → red "Bad posture" after a few
  seconds; test mode → the alert dialog after ~30 s of slouching. The model makes occasional mistakes, the same kind
  as in the desktop app; measured properly in Step 6.
- **First phone run:** ~24 pose FPS, ~4.3 model runs per second, ~0.7 ms per model run. With ~4.3 runs/s the
  smoothing needs 14 / 4.3 ≈ 3.3 s, so the red sign appears ≈ 6.3 s after slouching starts (the tests assume perfectly
  even frames and get 5.8 s).
- **Tests:** `PosturePipelineTest` (8 JVM tests, with a fake classifier): nothing is judged without a calibration;
  the classifier gets features − calibration; ~5 model runs per second at 23 FPS; no person → no model run;
  sitting straight stays good; slouching → sign after ~5.8 s, not before; test mode → alert after 30 s;
  new calibration starts from scratch. Removing the 5×/s limit is caught (230 instead of ~46 model runs in 10 s,
  and the sign would come much too early, because the 20-prediction window fills in under a second).

---

## 4. Problems and how they were solved

| # | Problem | Cause | Fix |
|---|---|---|---|
| P1 | Model loaded with a different scikit-learn could behave differently | Model saved with scikit-learn 1.3.2, which does not install on Python 3.13 | Step 0 runs in Python **3.11** with scikit-learn 1.3.2, the same as the project |
| P2 | `onnx` stopped working after installing MediaPipe | MediaPipe 0.10.21 needs `protobuf` 4.x; the newest `onnx` needs `protobuf` 5+ | Pinned `onnx==1.17.0` in `requirements-export.txt`; everything works in one `.venv` |
| P3 | Test-video evaluation differed from the real app | Training data (`collect_data.py`) and the live app (`server.py`, `calibration.py`) **mirror** the image; `evaluate_external_test.py` did **not** | Measured both (upright frames): mirroring is correct and ~1 point better (89.7% vs 88.5% with the new pose model). Android must rotate → mirror → detect |
| P4 | Self-test on the phone: **10/20 labels differ from Python** | Bug in **ONNX Runtime 1.27.0**: correct probabilities, but the `label` output was always 1 for our RandomForest. Reproduced in Python: 1.25.1 ✅, **1.26.0 ❌ (wrong probabilities and labels)**, **1.27.0 ❌ (wrong label)**, 1.28–1.32 ✅ | ONNX Runtime Android pinned to **1.25.1**; decision taken from probabilities; self-test also reports if the raw `label` is wrong. Rule in `AGENTS.md`. The cloud server must also avoid 1.26/1.27 |
| P5 | `testp6.mp4` was evaluated **upside down** (found in Step 2) | The phone stored the video with "rotate 180°" metadata; OpenCV 4.8 ignores it (`CAP_PROP_ORIENTATION_META` = 180, auto-rotation off). Noticed because the nose y was larger than the shoulder y | `compare_pose_models.py` now rotates frames upright from the metadata (`--no-autorotate` reproduces the old run). Step 0.3 re-measured: conclusions unchanged. The earlier `testp6` metrics in `evaluate_external_test.py` were also computed upside down; other phone videos may need the same check |
| P6 | Phone setup for development | Android apps are installed over USB from Android Studio | Developer options → USB debugging on; Samsung **Auto Blocker off** (it blocks USB commands); accept "Allow USB debugging" |
| P7 | `git status` shows almost every Android file as modified | Only line endings changed (Windows CRLF vs LF); `git diff --ignore-cr-at-eol` shows the real changes | Harmless; commit only the folders that really changed |
| P8 | CSV files never reach GitHub, including the training data | `.gitignore` ignores every `*.csv` file, so `data/posture_data.csv` and all result tables are not in the repo. A fresh clone cannot run `rebuild_scaler.py` (needed for `scaler.pkl`) or `check_onnx.py` | Add the needed files on purpose: `git add -f data/posture_data.csv` and the small result tables; large per-frame landmark files stay local |

---

## 5. Measurements (for the evaluation chapter)

| What | Value | Where measured | Step |
|---|---|---|---|
| Posture classification (ONNX), one sample | **0.11 ms** | Samsung, ONNX Runtime 1.25.1 | 1 |
| Pose model loading (MediaPipe) | 845–935 ms (once at start) | Samsung | 1 |
| ONNX vs Python max probability difference | 6.6e-07 (phone), 6.9e-07 (PC) | Phone / Python | 0.2, 1 |
| Accuracy, new pose model + mirroring, `testp6` (upright) | 89.69% (sens. 94.03%, spec. 87.98%) | Python, PC | 0.3 / P5 |
| Pose detection per frame (MediaPipe, LIVE_STREAM) | **~67 ms** | Samsung, 640×480 | 2 |
| Rotate + mirror per frame | **~5.6 ms** | Samsung | 2 |
| Pose frames per second | **~23 FPS** (5 needed for classification) | Samsung | 2 |
| Live monitoring: pose frames per second | **~24 FPS** | Samsung, posture good | 5 |
| Live monitoring: model runs per second | **~4.3/s** (limit "at most every 0.2 s"; frames arrive every ~42 ms, so a run happens ~0.23 s apart on average) | Samsung | 5 |
| Live monitoring: time per model run (in the pipeline) | **~0.7 ms** | Samsung | 5 |
| Cloud `/predict` latency | *to measure* | | 8–9 |
| Battery / heat over 1 h | *to measure* | Samsung | 6 |

At 5 classifications per second the classifier uses 5 × 0.11 ms = 0.55 ms per second of time (≈0.06%).
Per frame on the phone: 5.6 ms (rotate + mirror) + 67 ms (pose) + 0.11 ms (classifier) ≈ 72.7 ms, so pose
detection is ≈ 92% of the work (67 / 72.7). Moving only the classification to the cloud would save almost
nothing and add network time (to measure in Step 8).

---

## 6. Files added so far

| File | Step | Purpose                                                                       |
|---|---|-------------------------------------------------------------------------------|
| `src/export/export_onnx.py` | 0.1 | `.pkl` → `models/posture_model.onnx`                                          |
| `src/export/check_onnx.py` | 0.2 | Equality check + `models/onnx_test_cases.json`                                |
| `src/export/compare_pose_models.py` | 0.3 | Legacy vs Tasks pose model, with/without mirroring, auto-rotates phone videos |
| `requirements-export.txt` | 0 | `skl2onnx`, `onnx==1.17.0`, `onnxruntime`                                     |
| `models/posture_model.onnx`, `models/onnx_test_cases.json`, `models/pose_landmarker_full.task` | 0 | Models + test cases                                                           |
| `data/reports/testp6/pose_comparison_upright.csv` (+ `landmarks_*_mirror-yes_upright.csv`, local only) | 0.3 | Pose model comparison (upright, final)                                        |
| `data/reports/testp6/pose_comparison.csv` (+ `landmarks_*_mirror-yes.csv`, local only) | 0.3 | Same, upside-down run (superseded, kept for reference)                        |
| `android/` | 1 | Android project (wizard + libraries + manifest)                               |
| `android/AGENTS.md` | 1 | Rules for coding agents                                                       |
| `android/app/src/main/assets/*` | 1 | Copies of the model files + test cases                                        |
| `android/app/src/main/java/com/postureguard/selftest/*` | 1 | Temporary self-test screen                                                    |
| `android/app/src/main/java/com/postureguard/camera/*` | 2 | CameraX config + frame analyzer (rotate + mirror)                             |
| `android/app/src/main/java/com/postureguard/pose/*` | 2 | Pose data classes + MediaPipe wrapper                                         |
| `android/app/src/main/java/com/postureguard/ui/posedebug/*` | 2 | Pose debug ViewModel + screen                                                 |
| `android/app/src/main/java/com/postureguard/posture/*` | 3 | Features, calibration, throttle, state machine, ONNX classifier               |
| `android/app/src/test/java/com/postureguard/posture/*` | 3 | JVM unit tests (27)                                                           |
| `android/app/src/androidTest/java/com/postureguard/posture/LocalOnnxClassifierTest.kt` | 3 | ONNX classifier test on the phone                                             |
| `android/app/src/main/java/com/postureguard/calibration/*` | 4 | Calibration collector + DataStore repository                                  |
| `android/app/src/main/java/com/postureguard/ui/common/CameraPreview.kt` | 4 | Shared camera preview + landmark dots                                         |
| `android/app/src/main/java/com/postureguard/ui/calibration/*` | 4 | Calibration ViewModel + screen                                                |
| `android/app/src/test/java/com/postureguard/calibration/CalibrationCollectorTest.kt` | 4 | JVM unit tests (12)                                                           |
| `android/app/src/main/java/com/postureguard/posture/PosturePipeline.kt` | 5 | Per-frame chain: calibration → model (5×/s) → state machine |
| `android/app/src/main/java/com/postureguard/alert/AlertManager.kt` | 5 | 10-minute alert dialog state |
| `android/app/src/main/java/com/postureguard/ui/common/CameraPermission.kt` | 5 | Shared camera permission check |
| `android/app/src/main/java/com/postureguard/ui/monitor/*` | 5 | Monitoring ViewModel + screen |
| `android/app/src/test/java/com/postureguard/posture/PosturePipelineTest.kt` | 5 | JVM unit tests (8) |
| `docs/PROGRESS.md` | – | This report                                                                   |

### How to reproduce
```bash
# Python (3.11, from the repo root)
pip install -r requirements.txt -r requirements-export.txt
python src/training/rebuild_scaler.py          # scaler.pkl is not in git
python src/export/export_onnx.py
python src/export/check_onnx.py
python src/export/compare_pose_models.py --video testp6 --tag _upright   # needs data/test_frames/testp6.mp4 (not in git)
```
Android: open `android/` in Android Studio → Sync → Run on the phone → pose debug screen
(dots on nose and shoulders, both checks ✅).

---

## 7. Next: Step 6 (real test)
- Phone in its real position (end of the desk, lens about chin height); calibrate there.
- Repeat the `testp6`-style scenario (good / bad / good / bad, incl. mild slouching) and note what the app shows.
- 1-hour run while charging: battery and heat.

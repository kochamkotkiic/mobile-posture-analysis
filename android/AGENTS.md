# Project Instructions: PostureGuard (Android)

Android app that watches the user's sitting posture through the front camera and warns about bad posture.
Full plan, decisions and team split: `../.claude/skills/posture-mobile/SKILL.md` (read it before larger changes).

## Rules that must never be broken (the model depends on them)
- **Rotate the camera frame upright, then MIRROR it horizontally, then run pose detection.** The model was
  trained on mirrored images. Without mirroring, accuracy drops from ~88% to ~82% (Step 0.3).
- **Feature order** (model input, 9 floats): `nose_x, nose_y, nose_z, left_shoulder_x, left_shoulder_y,
  left_shoulder_z, right_shoulder_x, right_shoulder_y, right_shoulder_z` (MediaPipe landmarks 0, 11, 12,
  normalized coordinates).
- Model input = features **minus calibration**. The StandardScaler is **inside** `posture_model.onnx`:
  never scale again in Kotlin.
- Model output: `probabilities` = [P(good), P(bad)]. **Take the decision from the probabilities**
  (bad = 1 if P(bad) > P(good), else good = 0; same rule as sklearn). Do **not** use the `label` output:
  ONNX Runtime 1.27 returns it wrong (always 1) for this model.
- ONNX Runtime version: **1.25.1**. 1.26.x gives wrong probabilities and 1.27.x a wrong `label` for our
  RandomForest (tested with `posture_model.onnx`). Before upgrading, run the Step 1 self-test.
- Camera analysis resolution **4:3**, phone in **landscape**.
- Pose model: `pose_landmarker_full.task`, confidence 0.6 (monitoring) / 0.7 (calibration).
- Images/video never leave the phone. Only the 9 numbers may be sent to a server.
- Smoothing/timers exactly as SKILL.md section 5.5 (20 predictions, ≥14 bad, 3 s sign, 10 min alert).

## Project structure
- `app/src/main/java/com/postureguard/`
  - `MainActivity.kt`: single Activity, Compose `setContent`
  - `camera/`: CameraX setup
  - `pose/`: `PoseDetector` (MediaPipe wrapper)
  - `posture/`: `FeatureExtractor`, `Normalizer`, `PostureClassifier`, `LocalOnnxClassifier`,
    `PostureStateMachine`. **Plain Kotlin, no `android.*` imports**, unit-tested on the JVM.
  - `remote/`: `RemoteClassifier` (cloud `/predict`), latency logging
  - `calibration/`: `CalibrationManager`, `CalibrationRepository` (DataStore)
  - `alert/`: `AlertManager`
  - `settings/`: `SettingsRepository` (DataStore): classifier mode, server URL, developer tools switch
  - `cloud/`: `CloudApi` (HttpURLConnection + org.json, no extra libraries) for Nuno's API
    (`cloud/README.md`): `/me`, `/friends`, `/alerts`, `/tasks`. Base URL = the server address setting.
  - `account/`: `AccountRepository`, the signed-in user. For now a **test sign-in** for a server started with
    `DEV_AUTH=true` (token `dev:<uid>:<email>`); swap in Firebase Auth later without changing callers.
  - `ui/`: Compose screens + `ui/theme/`
    - `ui/theme/`: monochrome theme (black/white/greys); colour only for status (`StatusColors`). No dynamic colour.
    - `ui/components/`: shared building blocks (`PrimaryButton`, `SecondaryButton`, `Panel`, `StatTile`, ...).
      Use these instead of raw Material buttons so all screens look the same.
    - Screens: `welcome/` (first launch), `calibration/`, `monitor/` (home), `settings/` and `friends/`
      (layers over the monitor, so monitoring keeps running while they are open).
      Landscape layout everywhere: camera card on the left, content panel on the right.
    - Developer/evaluation tools (FPS, test mode, guided test, recording) are shown only when
      "Developer tools" is on in Settings; keep them working, they are needed for Step 9.
  - `selftest/`: Step 1 checkpoint, temporary (delete when the real screens exist)
- `app/src/main/assets/`: `pose_landmarker_full.task`, `posture_model.onnx`, `onnx_test_cases.json`.
  **Copies** of `../models/`; the source of truth is `../models/` (produced by `../src/export/`).
  After re-exporting the model, copy it here again.

## Architecture
- Recommended Android architecture, **MVVM** with unidirectional data flow:
  Compose screen observes `StateFlow` from a `ViewModel` via `collectAsState()`; events go back as callbacks.
- Composables stay stateless where possible; state lives in ViewModels.
- Heavy work (pose detection, model inference, model loading) never on the main thread
  (`Dispatchers.Default`, CameraX analyzer executor).
- Contracts shared with teammates (`PostureClassifier`, `Prediction`, `PostureState`, `PostureEvent`) are
  defined in SKILL.md section 6. Change them only together with the team.

## Coding rules
- Kotlin, Jetpack Compose, Material 3. Prefer immutable data (`val`, data classes).
- Libraries via the version catalog `gradle/libs.versions.toml` only.
- Write JUnit tests for `posture/` (use `onnx_test_cases.json` for the classifier; an injectable clock for
  the state machine).
- Small increments: change → build → test → commit.

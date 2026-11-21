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
- Model output: `label` (0 = good, 1 = bad), `probabilities` = [P(good), P(bad)].
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
  - `ui/`: Compose screens + `ui/theme/`
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

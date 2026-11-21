"""
Step 0.3: does the NEW pose model (MediaPipe Tasks PoseLandmarker, the one Android uses) give results close
to the OLD one (legacy mp.solutions.pose, used to build our model)?

Runs one test video through the same pipeline as evaluate_external_test.py / server.py:
  calibration (first CALIB_SECONDS s, confidence 0.7) -> per frame pose (confidence 0.6)
  -> 9 features - calibration -> scaler + RandomForest every 0.2 s -> carry-forward between inferences.
Then scores it with the SAME compute_metrics() as src/training/batch_metryki_test.py.

Ground truth is taken from the existing data/reports/<video>/wyniki_detekcji.csv (column ground_truth),
so old and new runs are scored against exactly the same labels.

Variants (all combinations are run, unless limited with --pose / --mirror):
  pose   = legacy | tasks      (tasks needs models/pose_landmarker_full.task)
  mirror = no | yes            (training data and the live app use cv2.flip(frame, 1); the old test
                                evaluation did NOT mirror -> "legacy, no mirror" reproduces the old numbers)

Usage (from the repo root, .venv active):
  python src/export/compare_pose_models.py --video testp6
Output: data/reports/<video>/pose_comparison.csv (+ per-variant detection CSVs)
"""
import argparse
import os
import sys

import cv2
import joblib
import mediapipe as mp
import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, confusion_matrix

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEST_VIDEOS_DIR = os.path.join(BASE_DIR, "data", "test_frames")
REPORTS_DIR = os.path.join(BASE_DIR, "data", "reports")
MODEL_PATH = os.path.join(BASE_DIR, "models", "posture_model.pkl")
SCALER_PATH = os.path.join(BASE_DIR, "models", "scaler.pkl")
TASK_MODEL_PATH = os.path.join(BASE_DIR, "models", "pose_landmarker_full.task")

# Same settings as server.py / evaluate_external_test.py
SMOOTH_N = 20
SMOOTH_THRESH = 0.70
SHORT_THRESH_S = 3.0
INFERENCE_INTERVAL = 0.2
CALIB_SECONDS = 5.0
CALIB_VIS_MIN = 0.6
CALIB_SAMPLES_MIN = 10
CALIB_CONF = 0.7
EVAL_CONF = 0.6
LANDMARKS = {"nose": 0, "left_shoulder": 11, "right_shoulder": 12}
FEATURE_KEYS = [f"{n}_{a}" for n in LANDMARKS for a in ["x", "y", "z"]]


# ── Pose backends: both return a list of 33 landmarks (x, y, z, visibility) or None ──────────────
class LegacyPose:
    def __init__(self, conf):
        self.pose = mp.solutions.pose.Pose(min_detection_confidence=conf, min_tracking_confidence=conf,
                                           model_complexity=1)

    def __call__(self, rgb, timestamp_ms):
        res = self.pose.process(rgb)
        return res.pose_landmarks.landmark if res.pose_landmarks else None

    def close(self):
        self.pose.close()


class TasksPose:
    def __init__(self, conf):
        from mediapipe.tasks import python as mp_tasks
        from mediapipe.tasks.python import vision
        options = vision.PoseLandmarkerOptions(
            base_options=mp_tasks.BaseOptions(model_asset_path=TASK_MODEL_PATH),
            running_mode=vision.RunningMode.VIDEO,
            num_poses=1,
            min_pose_detection_confidence=conf,
            min_pose_presence_confidence=conf,
            min_tracking_confidence=conf,
        )
        self.landmarker = vision.PoseLandmarker.create_from_options(options)

    def __call__(self, rgb, timestamp_ms):
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
        res = self.landmarker.detect_for_video(image, timestamp_ms)
        return res.pose_landmarks[0] if res.pose_landmarks else None

    def close(self):
        self.landmarker.close()


def make_pose(kind, conf):
    return LegacyPose(conf) if kind == "legacy" else TasksPose(conf)


# ── Pipeline (same logic as evaluate_external_test.py) ────────────────────────────────────────────
ROTATE = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}
UPRIGHT_ROTATION = 0  # set in main() from the video's rotation metadata


def frames(cap, mirror, start_frame=0, max_frames=None):
    """Yields RGB frames like the app sees them: rotated upright first, then (optionally) mirrored.
    OpenCV 4.8 does NOT apply the rotation stored in phone videos (testp6 is stored upside down)."""
    cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    n = 0
    while max_frames is None or n < max_frames:
        ok, frame = cap.read()
        if not ok:
            return
        if UPRIGHT_ROTATION in ROTATE:
            frame = cv2.rotate(frame, ROTATE[UPRIGHT_ROTATION])
        if mirror:
            frame = cv2.flip(frame, 1)
        n += 1
        yield start_frame + n, cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)


def calibrate(cap, fps, kind, mirror, start_sec):
    pose = make_pose(kind, CALIB_CONF)
    samples = []
    for frame_num, rgb in frames(cap, mirror, int(start_sec * fps), int(fps * CALIB_SECONDS)):
        lm = pose(rgb, int(frame_num * 1000 / fps))
        if lm is not None and (lm[11].visibility + lm[12].visibility) / 2.0 > CALIB_VIS_MIN:
            samples.append([v for idx in LANDMARKS.values() for v in (lm[idx].x, lm[idx].y, lm[idx].z)])
    pose.close()
    if len(samples) < CALIB_SAMPLES_MIN:
        print(f"    WARNING: only {len(samples)} calibration samples (minimum {CALIB_SAMPLES_MIN})")
    if not samples:
        return None, 0
    return dict(zip(FEATURE_KEYS, np.mean(samples, axis=0))), len(samples)


def evaluate(cap, fps, kind, mirror, calibration, model, scaler):
    pose = make_pose(kind, EVAL_CONF)
    every_n = max(1, int(fps * INFERENCE_INTERVAL))
    calib = np.array([calibration[k] for k in FEATURE_KEYS])
    buffer, last_raw, last_conf, last_label = [], 0, 1.0, 0
    rows, raw_rows = [], []
    for frame_num, rgb in frames(cap, mirror):
        lm = pose(rgb, int(frame_num * 1000 / fps))
        visible = lm is not None
        if visible:
            feats = np.array([v for idx in LANDMARKS.values() for v in (lm[idx].x, lm[idx].y, lm[idx].z)])
            raw_rows.append([frame_num, *feats])
            if frame_num % every_n == 0:
                X = scaler.transform((feats - calib).reshape(1, -1))
                last_raw = int(model.predict(X)[0])
                last_conf = float(model.predict_proba(X)[0][last_raw])
                buffer = (buffer + [last_raw])[-SMOOTH_N:]
                last_label = 1 if buffer.count(1) >= int(SMOOTH_N * SMOOTH_THRESH) else 0
        rows.append({
            "frame_num": frame_num,
            "prediction": last_raw if visible else -1,
            "confidence": round(last_conf, 4) if visible else -1,
            "smoothed_pred": last_label if visible else -1,
        })
    pose.close()
    raw = pd.DataFrame(raw_rows, columns=["frame_num", *FEATURE_KEYS])
    return pd.DataFrame(rows), raw


# ── Metrics: copied 1:1 from src/training/batch_metryki_test.py (only the numbers part) ───────────
def _runs(arr):
    arr = np.asarray(arr, dtype=int)
    if len(arr) == 0:
        return []
    runs, s, cur = [], 0, arr[0]
    for i in range(1, len(arr)):
        if arr[i] != cur:
            runs.append((int(cur), s, i - 1))
            cur, s = arr[i], i
    runs.append((int(cur), s, len(arr) - 1))
    return runs


def compute_metrics(df, fps):
    df = df.reset_index(drop=True).copy()
    df["pred_s"] = (df["prediction"].rolling(window=SMOOTH_N, min_periods=1).mean() >= SMOOTH_THRESH).astype(int)
    gt, pred = df["ground_truth"].values, df["pred_s"].values
    tn, fp, fn, tp = confusion_matrix(gt, pred, labels=[0, 1]).ravel()
    gt_s, pred_s = pd.Series(gt), pd.Series(pred)
    pred_runs = _runs(pred)
    fa = sum(1 for l, s, e in pred_runs if l == 1 and gt_s.iloc[s:e + 1].mean() < 0.5)
    det = [int(np.argmax(pred_s.iloc[s:e + 1].values)) / fps
           for l, s, e in _runs(gt) if l == 1 and pred_s.iloc[s:e + 1].values.any()]
    return {
        "Frames": len(df),
        "Acc. raw": round(accuracy_score(gt, df["prediction"].values) * 100, 2),
        "Acc. smooth": round(accuracy_score(gt, pred) * 100, 2),
        "Sensitivity": round(tp / (tp + fn) * 100, 2) if tp + fn else float("nan"),
        "Specificity": round(tn / (tn + fp) * 100, 2) if tn + fp else float("nan"),
        "FA/h": round(fa / (len(df) / fps) * 3600, 1),
        "Detection [s]": round(float(np.mean(det)), 2) if det else float("nan"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", default="testp6")
    ap.add_argument("--calib-start", type=float, default=0.0, help="second where calibration starts")
    ap.add_argument("--pose", choices=["legacy", "tasks"], nargs="+", default=["legacy", "tasks"])
    ap.add_argument("--mirror", choices=["no", "yes"], nargs="+", default=["no", "yes"])
    ap.add_argument("--no-autorotate", action="store_true",
                    help="ignore the video's rotation metadata (reproduces the old, upside-down evaluation)")
    ap.add_argument("--tag", default="", help="suffix for output file names")
    args = ap.parse_args()

    video_path = os.path.join(TEST_VIDEOS_DIR, f"{args.video}.mp4")
    out_dir = os.path.join(REPORTS_DIR, args.video)
    gt_csv = os.path.join(out_dir, "wyniki_detekcji.csv")
    gt = pd.read_csv(gt_csv)[["frame_num", "ground_truth"]]

    if "tasks" in args.pose and not os.path.exists(TASK_MODEL_PATH):
        print(f"Skipping 'tasks': {TASK_MODEL_PATH} not found.")
        args.pose = [p for p in args.pose if p != "tasks"]

    model, scaler = joblib.load(MODEL_PATH), joblib.load(SCALER_PATH)
    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 0)  # we rotate ourselves, so the behaviour is explicit
    global UPRIGHT_ROTATION
    meta = int(cap.get(cv2.CAP_PROP_ORIENTATION_META)) % 360
    UPRIGHT_ROTATION = 0 if args.no_autorotate else meta
    print(f"{args.video}: {int(cap.get(cv2.CAP_PROP_FRAME_COUNT))} frames, {fps:.1f} FPS, "
          f"rotation metadata {meta} deg -> rotating frames by {UPRIGHT_ROTATION} deg")

    results = []
    for kind in args.pose:
        for mirror in args.mirror:
            name = f"{kind}_mirror-{mirror}{args.tag}"
            print(f"\n== {name} ==")
            calibration, n_samples = calibrate(cap, fps, kind, mirror == "yes", args.calib_start)
            if calibration is None:
                print("    calibration failed")
                continue
            det, raw = evaluate(cap, fps, kind, mirror == "yes", calibration, model, scaler)
            det = det.drop(columns=[]).merge(gt, on="frame_num", how="left").fillna({"ground_truth": -1})
            det.to_csv(os.path.join(out_dir, f"wyniki_detekcji_{name}.csv"), index=False)
            raw.to_csv(os.path.join(out_dir, f"landmarks_{name}.csv"), index=False)
            scored = det[(det["prediction"] != -1) & (det["ground_truth"] != -1)]
            visible_pct = (det["prediction"] != -1).mean() * 100
            m = {"Variant": name, "Rotation": UPRIGHT_ROTATION, "Calib samples": n_samples, "Person visible %": round(visible_pct, 1),
                 **compute_metrics(scored, fps)}
            print("   ", m)
            results.append(m)
    cap.release()

    table = pd.DataFrame(results)
    out_csv = os.path.join(out_dir, f"pose_comparison{args.tag}.csv")
    table.to_csv(out_csv, index=False)
    pd.set_option("display.width", 200)
    print("\n" + table.to_string(index=False))
    print(f"\nSaved {out_csv}")


if __name__ == "__main__":
    sys.exit(main())

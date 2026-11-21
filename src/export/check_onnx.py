"""
Step 0.2: check that models/posture_model.onnx gives the SAME answers as scaler.pkl + posture_model.pkl,
and save test cases for the Kotlin unit tests (models/onnx_test_cases.json).

Inputs used: every row of data/posture_data.csv, normalized the same way as in training
(features - mean of the good-posture rows), i.e. realistic "features - calibration" values.

Pass criteria: all labels equal, probabilities equal within 1e-5.

Usage (from the repo root, .venv active):  python src/export/check_onnx.py
"""
import json
import os
import sys

import joblib
import numpy as np
import onnxruntime as ort
import pandas as pd

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CSV_PATH = os.path.join(BASE_DIR, "data", "posture_data.csv")
MODEL_PATH = os.path.join(BASE_DIR, "models", "posture_model.pkl")
SCALER_PATH = os.path.join(BASE_DIR, "models", "scaler.pkl")
ONNX_PATH = os.path.join(BASE_DIR, "models", "posture_model.onnx")
CASES_PATH = os.path.join(BASE_DIR, "models", "onnx_test_cases.json")

LANDMARKS = ["nose", "left_shoulder", "right_shoulder"]
FEATURE_COLS = [f"{lm}_{ax}" for lm in LANDMARKS for ax in ["x", "y", "z"]]  # order = model input order
TOLERANCE = 1e-5
N_CASES = 20

# --- inputs (same normalization as train_model.ipynb / rebuild_scaler.py) ---
df = pd.read_csv(CSV_PATH)
X = df[FEATURE_COLS] - df[df["label"] == 0][FEATURE_COLS].mean()
X32 = X.to_numpy(dtype=np.float32)

# --- reference: the .pkl files, exactly like server.py ---
scaler = joblib.load(SCALER_PATH)
model = joblib.load(MODEL_PATH)
scaled = scaler.transform(X32.astype(np.float64))
ref_label = model.predict(scaled)
ref_proba = model.predict_proba(scaled)

# --- candidate: the .onnx file, like the Android app / server will run it ---
session = ort.InferenceSession(ONNX_PATH, providers=["CPUExecutionProvider"])
onnx_label, onnx_proba = session.run(["label", "probabilities"], {"features": X32})

# --- compare ---
n = len(X32)
label_mismatch = np.flatnonzero(ref_label != onnx_label)
proba_diff = np.abs(ref_proba - onnx_proba).max(axis=1)
max_diff = proba_diff.max()

print(f"Rows compared:          {n}")
print(f"Label mismatches:       {len(label_mismatch)}")
print(f"Max probability diff:   {max_diff:.2e}  (tolerance {TOLERANCE:.0e})")
print(f"Rows over tolerance:    {(proba_diff > TOLERANCE).sum()}")
print(f"Accuracy vs true label: pkl {np.mean(ref_label == df['label']):.4f} | onnx {np.mean(onnx_label == df['label']):.4f}")
if len(label_mismatch):
    print("First mismatching rows:", label_mismatch[:10].tolist())

ok = len(label_mismatch) == 0 and max_diff <= TOLERANCE

# --- test cases for Kotlin: clear good, clear bad, and the most borderline rows ---
p_bad = ref_proba[:, 1]
order = np.argsort(np.abs(p_bad - 0.5))
borderline = order[:6].tolist()
rng = np.random.default_rng(42)
good_rows = rng.choice(np.flatnonzero(ref_label == 0), 7, replace=False).tolist()
bad_rows = rng.choice(np.flatnonzero(ref_label == 1), 7, replace=False).tolist()
rows = list(dict.fromkeys(borderline + good_rows + bad_rows))[:N_CASES]

cases = {
    "description": "Inputs are normalized features (features - calibration), order: " + ", ".join(FEATURE_COLS)
                   + ". Expected outputs come from the .pkl reference. label: 0 good, 1 bad. "
                   "probabilities: [P(good), P(bad)]. Compare probabilities with tolerance.",
    "tolerance": TOLERANCE,
    "cases": [
        {
            "row": int(i),
            "input": [float(v) for v in X32[i]],
            "label": int(ref_label[i]),
            "probabilities": [float(ref_proba[i, 0]), float(ref_proba[i, 1])],
        }
        for i in rows
    ],
}
with open(CASES_PATH, "w") as f:
    json.dump(cases, f, indent=2)
print(f"Saved {len(rows)} test cases to {CASES_PATH} "
      f"({sum(c['label'] == 0 for c in cases['cases'])} good, {sum(c['label'] == 1 for c in cases['cases'])} bad, "
      f"{len(borderline)} borderline)")

print("PASS: ONNX matches the .pkl model" if ok else "FAIL: ONNX differs from the .pkl model")
sys.exit(0 if ok else 1)

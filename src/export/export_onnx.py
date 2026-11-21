"""
Step 0.1: export scaler.pkl + posture_model.pkl as ONE ONNX file (models/posture_model.onnx).

Why: the Android app (ONNX Runtime Android) and the cloud/edge /predict server (onnxruntime in Python)
both load this same file, so all three tiers make exactly the same decision.

Input  : "features", float32 [N, 9]  -> already NORMALIZED (features - calibration), order:
         nose_x, nose_y, nose_z, left_shoulder_x/y/z, right_shoulder_x/y/z
Outputs: "label"        int64 [N]     0 = good, 1 = bad
         "probabilities" float32 [N, 2] [P(good), P(bad)]  (zipmap disabled -> plain array)
The StandardScaler is INSIDE the ONNX file, so the app must NOT scale again.

Usage (from the repo root, .venv active):  python src/export/export_onnx.py
Needs: pip install skl2onnx onnx onnxruntime   (scikit-learn must stay 1.3.2, same as the model)
"""
import os

import joblib
import onnx
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
from sklearn.pipeline import Pipeline

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODEL_PATH = os.path.join(BASE_DIR, "models", "posture_model.pkl")
SCALER_PATH = os.path.join(BASE_DIR, "models", "scaler.pkl")
ONNX_PATH = os.path.join(BASE_DIR, "models", "posture_model.onnx")

N_FEATURES = 9
TARGET_OPSET = 15  # widely supported by ONNX Runtime Android

scaler = joblib.load(SCALER_PATH)
model = joblib.load(MODEL_PATH)

assert scaler.n_features_in_ == N_FEATURES, f"scaler expects {scaler.n_features_in_} features"
assert model.n_features_in_ == N_FEATURES, f"model expects {model.n_features_in_} features"
assert list(model.classes_) == [0, 1], f"unexpected classes {model.classes_}"

pipeline = Pipeline([("scaler", scaler), ("model", model)])

onx = convert_sklearn(
    pipeline,
    initial_types=[("features", FloatTensorType([None, N_FEATURES]))],
    options={id(model): {"zipmap": False}},
    target_opset=TARGET_OPSET,
)
onnx.checker.check_model(onx)

with open(ONNX_PATH, "wb") as f:
    f.write(onx.SerializeToString())

print(f"Saved {ONNX_PATH} ({os.path.getsize(ONNX_PATH) / 1024:.0f} KB)")
print("Inputs :", [(i.name, [d.dim_value or "N" for d in i.type.tensor_type.shape.dim]) for i in onx.graph.input])
print("Outputs:", [o.name for o in onx.graph.output])

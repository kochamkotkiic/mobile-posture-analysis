import numpy as np
import onnxruntime as ort

class PostureModel:
    def __init__(self, path):
        # Load once at startup: loading takes far longer than a prediction
        self.session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    def predict(self, samples: list[list[float]]) -> list[dict]:
        x = np.asarray(samples, dtype=np.float32)            # shape [N, 9]
        _, probs = self.session.run(["label", "probabilities"], {"features": x})
        out = []
        for p_good, p_bad in probs:
            if p_bad > p_good:
                out.append({"label": 1, "confidence": float(p_bad)})
            else:
                out.append({"label": 0, "confidence": float(p_good)})
        return out
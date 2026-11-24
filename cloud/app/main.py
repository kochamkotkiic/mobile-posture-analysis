import time
from contextlib import asynccontextmanager
from fastapi import FastAPI
from pydantic import BaseModel, Field
from .config import settings
from .model import PostureModel

Features = list[float]

class PredictRequest(BaseModel):
    features: Features = Field(min_length=9, max_length=9)

class BatchRequest(BaseModel):
    samples: list[Features] = Field(min_length=1, max_length=settings.max_batch)

class Prediction(BaseModel):
    label: int
    confidence: float

class BatchResponse(BaseModel):
    predictions: list[Prediction]

model: PostureModel | None = None

@asynccontextmanager
async def lifespan(app):
    global model
    model = PostureModel(settings.model_path)
    yield

app = FastAPI(title="PostureGuard /predict", lifespan=lifespan)

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/predict", response_model=Prediction)
def predict(req: PredictRequest):
    return model.predict([req.features])[0]

@app.post("/predict/batch", response_model=BatchResponse)
def predict_batch(req: BatchRequest):
    for s in req.samples:
        if len(s) != 9:
            from fastapi import HTTPException
            raise HTTPException(422, "each sample must have 9 values")
    return {"predictions": model.predict(req.samples)}
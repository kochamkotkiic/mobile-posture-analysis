import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from . import auth
from .config import settings
from .db import run_migrations
from .model import PostureModel
from .model_store import resolve_model_path
from .routers import alerts, friends, me, model_files, tasks

Features = list[float]


class PredictRequest(BaseModel):
    features: Features = Field(min_length=9, max_length=9)


class BatchRequest(BaseModel):
    samples: list[Features] = Field(min_length=1, max_length=settings.max_batch)


class Prediction(BaseModel):
    label: int            # 0 good, 1 bad
    confidence: float     # probability of the chosen label


class PredictResponse(Prediction):
    server_ms: float      # model time on the server (phone: round trip - server_ms = network)


class BatchResponse(BaseModel):
    predictions: list[Prediction]
    server_ms: float


# Random id of this server process. Every response carries it in the X-Instance-Id header, so the
# load test can count how many instances Cloud Run started (= autoscaling made visible).
INSTANCE_ID = uuid.uuid4().hex[:8]


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.model_path = resolve_model_path()           # from the Cloud Storage bucket if configured
    app.state.model = PostureModel(app.state.model_path)  # load once
    if settings.migrate_on_startup:
        run_migrations()
    auth.init_firebase()
    yield


app = FastAPI(title="PostureGuard API", lifespan=lifespan)
for r in (me.router, friends.router, alerts.router, tasks.router, model_files.router):
    app.include_router(r)


@app.middleware("http")
async def add_instance_id(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Instance-Id"] = INSTANCE_ID
    return response


@app.get("/health")
def health():
    return {"status": "ok", "instance": INSTANCE_ID}


# /predict is public on purpose: the evaluation measures network + model only, no token check.
@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    t0 = time.perf_counter()
    p = app.state.model.predict([req.features])[0]
    return {**p, "server_ms": (time.perf_counter() - t0) * 1000}


@app.post("/predict/batch", response_model=BatchResponse)
def predict_batch(req: BatchRequest):
    if any(len(s) != 9 for s in req.samples):
        raise HTTPException(422, "each sample must have 9 values")
    t0 = time.perf_counter()
    preds = app.state.model.predict(req.samples)
    return {"predictions": preds, "server_ms": (time.perf_counter() - t0) * 1000}

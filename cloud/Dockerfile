FROM python:3.11-slim
WORKDIR /app
COPY cloud/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY cloud/app ./app
COPY models/posture_model.onnx ./model.onnx
ENV MODEL_PATH=/app/model.onnx
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
"""Upload the posture model to the Cloud Storage bucket the server reads it from.

    cd cloud && source .venv/bin/activate
    export GOOGLE_APPLICATION_CREDENTIALS="$PWD/secrets/<service-account>.json"
    python scripts/upload_model.py --bucket mobilecloud-7dd8f-models

Then restart the Cloud Run service (or deploy a new revision) so it downloads the new file."""
import argparse
import hashlib
from pathlib import Path

from google.cloud import storage

REPO_ROOT = Path(__file__).resolve().parents[2]

parser = argparse.ArgumentParser()
parser.add_argument("--bucket", required=True)
parser.add_argument("--file", default=str(REPO_ROOT / "models" / "posture_model.onnx"))
parser.add_argument("--object", default="models/posture_model.onnx")
parser.add_argument("--project", default="mobilecloud-7dd8f")
args = parser.parse_args()

data = Path(args.file).read_bytes()
blob = storage.Client(project=args.project).bucket(args.bucket).blob(args.object)
blob.metadata = {"sha256": hashlib.sha256(data).hexdigest()}
blob.upload_from_filename(args.file, content_type="application/octet-stream")
print(f"uploaded {args.file} -> gs://{args.bucket}/{args.object} "
      f"({len(data)} bytes, sha256 {blob.metadata['sha256'][:12]}…)")

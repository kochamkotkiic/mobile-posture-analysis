"""Load test for /predict: latency under load + autoscaling made visible.

Sends requests from many simulated phones at once and reports latency (mean, p50, p95, p99),
throughput, errors, the network share of the latency (round trip - server_ms), and how many
server instances answered (X-Instance-Id header) and when each one appeared.

    python scripts/loadtest.py --url https://posture-api-xxxx.europe-west8.run.app \\
        --concurrency 50 --duration 60 --csv results/load_c50.csv

--batch 5 sends 5 samples per request (the phone's cloud mode: 5 samples once per second).
Only the 9 posture numbers are sent, as in the app."""
import argparse
import asyncio
import csv
import random
import statistics
import time
from pathlib import Path

import httpx


def percentile(values, p):
    values = sorted(values)
    if not values:
        return float("nan")
    k = (len(values) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def sample():
    return [random.uniform(-0.05, 0.05) for _ in range(9)]   # realistic normalized deltas


async def worker(client, url, batch, stop_at, results, t0):
    while time.perf_counter() < stop_at:
        if batch > 1:
            path, body = "/predict/batch", {"samples": [sample() for _ in range(batch)]}
        else:
            path, body = "/predict", {"features": sample()}
        start = time.perf_counter()
        try:
            r = await client.post(url + path, json=body)
            rtt = (time.perf_counter() - start) * 1000
            ok = r.status_code == 200
            server_ms = r.json().get("server_ms") if ok else None
            results.append({"t": start - t0, "rtt_ms": rtt, "server_ms": server_ms, "status": r.status_code,
                            "instance": r.headers.get("x-instance-id", "?")})
        except httpx.HTTPError as e:
            results.append({"t": start - t0, "rtt_ms": (time.perf_counter() - start) * 1000,
                            "server_ms": None, "status": type(e).__name__, "instance": "-"})


async def main(args):
    url = args.url.rstrip("/")
    results = []
    limits = httpx.Limits(max_connections=args.concurrency, max_keepalive_connections=args.concurrency)
    async with httpx.AsyncClient(timeout=30, limits=limits) as client:
        await client.get(url + "/health")          # wake the service (cold start not counted)
        t0 = time.perf_counter()
        stop_at = t0 + args.duration
        await asyncio.gather(*(worker(client, url, args.batch, stop_at, results, t0)
                               for _ in range(args.concurrency)))
    elapsed = time.perf_counter() - t0

    ok = [r for r in results if r["status"] == 200]
    rtts = [r["rtt_ms"] for r in ok]
    servers = [r["server_ms"] for r in ok if r["server_ms"] is not None]
    print(f"\n{url}  concurrency={args.concurrency}  duration={args.duration}s  batch={args.batch}")
    print(f"requests: {len(results)}  ok: {len(ok)}  errors: {len(results) - len(ok)}"
          f"  throughput: {len(ok) / elapsed:.1f} req/s ({len(ok) * args.batch / elapsed:.1f} samples/s)")
    if rtts:
        print(f"round trip ms: mean {statistics.mean(rtts):.1f}  p50 {percentile(rtts, 50):.1f}"
              f"  p95 {percentile(rtts, 95):.1f}  p99 {percentile(rtts, 99):.1f}  max {max(rtts):.1f}")
    if servers:
        print(f"model on server ms: mean {statistics.mean(servers):.2f}"
              f"  -> network + queueing ≈ {statistics.mean(rtts) - statistics.mean(servers):.1f} ms")

    first_seen, counts = {}, {}
    for r in ok:
        counts[r["instance"]] = counts.get(r["instance"], 0) + 1
        first_seen[r["instance"]] = min(first_seen.get(r["instance"], 1e9), r["t"])
    print(f"instances that answered: {len(counts)}")
    for inst, t in sorted(first_seen.items(), key=lambda x: x[1]):
        print(f"  {inst}: first answer at {t:5.1f}s, {counts[inst]} requests")

    if args.csv:
        Path(args.csv).parent.mkdir(parents=True, exist_ok=True)
        with open(args.csv, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["t", "rtt_ms", "server_ms", "status", "instance"])
            w.writeheader()
            w.writerows(sorted(results, key=lambda r: r["t"]))
        print(f"per-request data: {args.csv}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8000")
    p.add_argument("--concurrency", type=int, default=20, help="simultaneous simulated phones")
    p.add_argument("--duration", type=float, default=30, help="seconds")
    p.add_argument("--batch", type=int, default=1, help="samples per request (1 = /predict)")
    p.add_argument("--csv", help="write every request to this CSV")
    asyncio.run(main(p.parse_args()))

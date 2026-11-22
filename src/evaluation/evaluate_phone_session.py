"""
Step 6: evaluate a session recorded by the Android app (Monitoring screen -> "Start guided test" / "Record session").

Copy the session folder from the phone to the PC first (Android Studio -> Device Explorer ->
/sdcard/Android/data/com.postureguard/files/sessions/<name>/ -> right click -> Save As), e.g. to
data/phone_sessions/<name>/. The folder contains frames.csv, battery.csv and meta.csv.

Usage (from the repo root):
    python src/evaluation/evaluate_phone_session.py data/phone_sessions/20261122_201500_guided

Guided test (ground truth from the app's script) - three levels are evaluated:
    model    : every single model answer (new_prediction)
    smoothed : the 14-of-20 smoothed label, every frame
    sign     : the red "Bad posture" sign = what the user actually sees, every frame
each on all frames and "settled" (without the first SETTLE_S seconds of every step: the sign is designed to need
~6 s, so counting that delay as an error would be unfair), plus a per-step table (delay to the sign, false alarms)
and mild vs strong slouching.
Every session: battery and temperature summary from battery.csv.

Output: printed tables + evaluation_summary.csv, evaluation_steps.csv and timeline.png in the session folder.
"""
import argparse
import os
import sys

import numpy as np
import pandas as pd

SETTLE_S = 6.0


def rates(gt, pred):
    """Accuracy / sensitivity (bad detected) / specificity (good recognised) in %, like batch_metryki_test.py."""
    gt, pred = np.asarray(gt, dtype=int), np.asarray(pred, dtype=int)
    tp = int(((gt == 1) & (pred == 1)).sum())
    tn = int(((gt == 0) & (pred == 0)).sum())
    fp = int(((gt == 0) & (pred == 1)).sum())
    fn = int(((gt == 1) & (pred == 0)).sum())
    n = len(gt)
    pct = lambda a, b: round(100.0 * a / b, 2) if b else float("nan")
    return {"n": n, "accuracy": pct(tp + tn, n), "sensitivity": pct(tp, tp + fn), "specificity": pct(tn, tn + fp)}


def load(folder):
    frames = pd.read_csv(os.path.join(folder, "frames.csv"))
    battery_path = os.path.join(folder, "battery.csv")
    battery = pd.read_csv(battery_path) if os.path.exists(battery_path) else None
    meta_path = os.path.join(folder, "meta.csv")
    meta = dict(pd.read_csv(meta_path, dtype=str).values) if os.path.exists(meta_path) else {}
    return frames, battery, meta


def add_step_time(frames):
    """Seconds since the start of the current scenario step."""
    step_start = frames.groupby("step")["t_ms"].transform("min")
    frames["t_in_step_s"] = (frames["t_ms"] - step_start) / 1000.0
    return frames


def level_table(frames):
    """Model / smoothed / sign metrics on all frames and on settled frames."""
    rows = []
    evaluated = frames[frames["ground_truth"].isin([0, 1])]
    visible = evaluated[evaluated["person_visible"] == 1]
    for subset_name, subset in [("all", visible), ("settled", visible[visible["t_in_step_s"] >= SETTLE_S])]:
        model = subset[subset["new_prediction"].isin([0, 1])]
        for level, gt, pred in [
            ("model", model["ground_truth"], model["new_prediction"]),
            ("smoothed", subset["ground_truth"], subset["smoothed"]),
            ("sign", subset["ground_truth"], subset["sign_shown"]),
        ]:
            rows.append({"frames": subset_name, "level": level, **rates(gt, pred)})
    return pd.DataFrame(rows)


def kind_table(frames):
    """Mild vs strong slouching (and good), sign level, settled frames."""
    rows = []
    settled = frames[(frames["person_visible"] == 1) & (frames["t_in_step_s"] >= SETTLE_S)]
    for kind in ["good", "mild", "strong"]:
        part = settled[settled["segment"] == kind]
        if part.empty:
            continue
        model = part[part["new_prediction"].isin([0, 1])]
        target = 0 if kind == "good" else 1
        rows.append({
            "segment": kind,
            "model_correct_%": round(100 * (model["new_prediction"] == target).mean(), 2) if len(model) else float("nan"),
            "smoothed_correct_%": round(100 * (part["smoothed"] == target).mean(), 2),
            "sign_correct_%": round(100 * (part["sign_shown"] == target).mean(), 2),
        })
    return pd.DataFrame(rows)


def step_table(frames):
    """One row per scenario step: delay to the sign (bad steps), false-alarm time (good steps)."""
    rows = []
    for step, part in frames[frames["ground_truth"].isin([0, 1])].groupby("step"):
        kind = part["segment"].iloc[0]
        duration = (part["t_ms"].iloc[-1] - part["t_ms"].iloc[0]) / 1000.0
        sign_on = part[part["sign_shown"] == 1]
        frame_s = part["t_ms"].diff().fillna(0).clip(upper=500) / 1000.0
        row = {
            "step": int(step),
            "segment": kind,
            "duration_s": round(duration, 1),
            "person_visible_%": round(100 * part["person_visible"].mean(), 1),
            "model_bad_%": round(100 * (part.loc[part["new_prediction"] >= 0, "new_prediction"] == 1).mean(), 1),
            "sign_shown_%": round(100 * part["sign_shown"].mean(), 1),
        }
        if kind == "good":
            row["false_sign_s"] = round(float(frame_s[part["sign_shown"] == 1].sum()), 1)
            row["sign_delay_s"] = ""
        else:
            row["false_sign_s"] = ""
            row["sign_delay_s"] = round(float(sign_on["t_in_step_s"].iloc[0]), 1) if len(sign_on) else "never"
        rows.append(row)
    return pd.DataFrame(rows)


def false_alarms(frames):
    """Number of times the red sign switched ON while the user was told to sit straight."""
    f = frames[frames["ground_truth"].isin([0, 1])].reset_index(drop=True)
    turned_on = (f["sign_shown"] == 1) & (f["sign_shown"].shift(fill_value=0) == 0)
    return int((turned_on & (f["ground_truth"] == 0)).sum())


def battery_summary(battery):
    if battery is None or battery.empty:
        return None
    hours = (battery["t_ms"].iloc[-1] - battery["t_ms"].iloc[0]) / 3_600_000
    discharging = battery[battery["charging"] == 0]
    drain_per_hour = float("nan")
    if len(discharging) >= 2:
        h = (discharging["t_ms"].iloc[-1] - discharging["t_ms"].iloc[0]) / 3_600_000
        if h > 0:
            drain_per_hour = round((discharging["battery_percent"].iloc[0] - discharging["battery_percent"].iloc[-1]) / h, 2)
    current = pd.to_numeric(battery["current_ua"], errors="coerce").dropna()
    return {
        "duration_min": round(hours * 60, 1),
        "battery_start_%": int(battery["battery_percent"].iloc[0]),
        "battery_end_%": int(battery["battery_percent"].iloc[-1]),
        "charging_whole_time": bool((battery["charging"] == 1).all()),
        "drain_%_per_hour (unplugged part)": drain_per_hour,
        "temperature_start_C": float(battery["temperature_c"].iloc[0]),
        "temperature_max_C": float(battery["temperature_c"].max()),
        "thermal_status_max (0=none..6=shutdown)": int(battery["thermal_status"].max()),
        "mean_current_mA (sign depends on phone)": round(float(current.mean()) / 1000, 1) if len(current) else float("nan"),
    }


def plot_timeline(frames, out_path):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not installed - no timeline.png")
        return
    t = frames["t_ms"] / 1000.0
    fig, ax = plt.subplots(figsize=(14, 4.5))
    colors = {"good": "#c8e6c9", "mild": "#ffe0b2", "strong": "#ffcdd2", "prepare": "#eeeeee"}
    for _, part in frames.groupby("step"):
        seg = part["segment"].iloc[0]
        if isinstance(seg, str) and seg in colors:
            ax.axvspan(part["t_ms"].iloc[0] / 1000, part["t_ms"].iloc[-1] / 1000, color=colors[seg], alpha=0.8, lw=0)
            ax.text((part["t_ms"].iloc[0] + part["t_ms"].iloc[-1]) / 2000, 1.32, seg, ha="center", fontsize=9)
    model = frames[frames["new_prediction"] >= 0]
    ax.scatter(model["t_ms"] / 1000, model["new_prediction"] * 1.0 + 0.04, s=4, color="#757575", label="model answer")
    ax.step(t, frames["smoothed"] * 0.9 + 0.05, where="post", color="#1565c0", lw=1.2, label="smoothed (14 of 20)")
    ax.step(t, frames["sign_shown"] * 0.8 + 0.1, where="post", color="#c62828", lw=2, label='red "Bad posture" sign')
    ax.step(t, frames["ground_truth"].clip(lower=0) * 1.0, where="post", color="black", lw=1, ls="--", label="ground truth (script)")
    ax.set_ylim(-0.15, 1.45)
    ax.set_yticks([0, 1], ["good", "bad"])
    ax.set_xlabel("time [s]")
    ax.set_title("Phone guided test: script vs app output")
    ax.legend(loc="upper left", bbox_to_anchor=(1.0, 1.0), fontsize=8)
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"Saved {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session", help="folder with frames.csv / battery.csv / meta.csv")
    args = ap.parse_args()

    frames, battery, meta = load(args.session)
    pd.set_option("display.width", 200)
    print(f"Session: {args.session}")
    for k in ["type", "started", "phone", "android", "scenario"]:
        if k in meta:
            print(f"  {k}: {meta[k]}")
    duration = (frames["t_ms"].iloc[-1] - frames["t_ms"].iloc[0]) / 1000 if len(frames) else 0
    fps = len(frames) / duration if duration else float("nan")
    print(f"  frames: {len(frames)} in {duration:.0f} s ({fps:.1f} FPS), "
          f"person visible {100 * frames['person_visible'].mean():.1f}%, "
          f"model runs {int((frames['new_prediction'] >= 0).sum())} "
          f"({(frames['new_prediction'] >= 0).sum() / duration if duration else float('nan'):.1f}/s)")

    summary_rows = []
    if (frames["ground_truth"] >= 0).any():
        frames = add_step_time(frames)
        levels = level_table(frames)
        print(f"\n== Accuracy (person visible; 'settled' = without the first {SETTLE_S:.0f} s of each step) ==")
        print(levels.to_string(index=False))
        kinds = kind_table(frames)
        print("\n== Mild vs strong slouching (settled frames, % of time correct) ==")
        print(kinds.to_string(index=False))
        steps = step_table(frames)
        print("\n== Per step ==")
        print(steps.to_string(index=False))
        fa = false_alarms(frames)
        print(f"\nFalse alarms (red sign switched on while sitting straight): {fa}")
        steps.to_csv(os.path.join(args.session, "evaluation_steps.csv"), index=False)
        summary_rows += [{"metric": f"{r['level']}_{r['frames']}_{m}", "value": r[m]}
                         for _, r in levels.iterrows() for m in ["accuracy", "sensitivity", "specificity"]]
        summary_rows += [{"metric": f"{r['segment']}_sign_correct_%", "value": r["sign_correct_%"]} for _, r in kinds.iterrows()]
        delays = pd.to_numeric(steps.loc[steps["segment"] != "good", "sign_delay_s"], errors="coerce")
        summary_rows.append({"metric": "mean_sign_delay_s", "value": round(float(delays.mean()), 2)})
        summary_rows.append({"metric": "false_alarms", "value": fa})
        plot_timeline(frames, os.path.join(args.session, "timeline.png"))
    else:
        print("\n(no ground truth: free recording - only battery / heat)")

    bat = battery_summary(battery)
    if bat:
        print("\n== Battery / heat ==")
        for k, v in bat.items():
            print(f"  {k}: {v}")
        summary_rows += [{"metric": k, "value": v} for k, v in bat.items()]

    summary_rows.append({"metric": "fps", "value": round(fps, 1)})
    pd.DataFrame(summary_rows).to_csv(os.path.join(args.session, "evaluation_summary.csv"), index=False)
    print(f"\nSaved {os.path.join(args.session, 'evaluation_summary.csv')}")


if __name__ == "__main__":
    sys.exit(main())

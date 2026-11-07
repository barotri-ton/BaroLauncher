#!/usr/bin/env python3
"""Strict API 28 meminfo, launch and frame evidence parser."""
import csv
import re
import statistics
import sys
from pathlib import Path


def stats(values):
    return {"n": len(values), "median": statistics.median(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None}


def parse_mem(text):
    # The API28 table has columns Pss Total, Private Dirty, Private Clean, SwapPss,
    # Heap Size, Heap Alloc, Heap Free. Never confuse PSS with allocated heap.
    result = {}
    for name, key in (("TOTAL", "total_pss_kb"), ("Dalvik Heap", "dalvik_pss_kb"),
                      ("Native Heap", "native_pss_kb"), ("Graphics", "graphics_pss_kb")):
        m = re.search(r"^\s*" + re.escape(name) + r"\s+([\d,]+)(?:\s|$)", text, re.M)
        result[key] = int(m.group(1).replace(",", "")) if m else None
    for name, prefix in (("Dalvik Heap", "java"), ("Native Heap", "native")):
        m = re.search(r"^\s*" + re.escape(name) + r"\s+(?:[\d,]+\s+){4}([\d,]+)\s+([\d,]+)\s+([\d,]+)", text, re.M)
        result[prefix + "_heap_size_kb"] = int(m.group(1).replace(",", "")) if m else None
        result[prefix + "_heap_alloc_kb"] = int(m.group(2).replace(",", "")) if m else None
    return result


def parse_frames(text):
    frames = {}
    inside = False
    for line in text.splitlines():
        if line.startswith("---PROFILEDATA---"):
            inside = not inside
            continue
        if not inside or line.startswith("Flags,"):
            continue
        cols = line.split(",")
        if len(cols) < 14:
            continue
        try:
            flags, vsync, completed = int(cols[0]), int(cols[1]), int(cols[13])
            if flags == 0 and completed > vsync > 0:
                frames[(vsync, completed)] = (completed - vsync) / 1_000_000
        except ValueError:
            continue
    return frames


def parse_start(text):
    m = re.search(r"^TotalTime:\s*(\d+)\s*$", text, re.M)
    return int(m.group(1)) if m else None


def main(root):
    root = Path(root)
    manifest_path = root / "manifest.txt"
    if not manifest_path.exists():
        raise ValueError("manifest.txt missing")
    metadata = dict(line.split("=", 1) for line in manifest_path.read_text().splitlines() if "=" in line)
    required = ("kind", "api", "runs", "network_state", "feature_toggles",
                "apk_sha256", "apk_provenance", "installed_app_count")
    if any(not metadata.get(k) or "RECORD_" in metadata[k] for k in required):
        raise ValueError("Required metadata missing or placeholder")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", metadata["apk_sha256"]):
        raise ValueError("Invalid APK SHA-256")
    installed = metadata.get("installed_apk_sha256", "UNAVAILABLE")
    if installed != "UNAVAILABLE" and not re.fullmatch(r"[a-fA-F0-9]{64}", installed):
        raise ValueError("Invalid installed APK SHA-256")
    if installed != "UNAVAILABLE" and installed.lower() != metadata["apk_sha256"].lower():
        raise ValueError("Installed APK SHA differs from local artifact")
    if metadata["network_state"] not in ("on", "off"):
        raise ValueError("Invalid network state")
    if not metadata["installed_app_count"].isdigit():
        raise ValueError("Invalid installed app count")
    count = int(metadata["runs"])
    if count < 1:
        raise ValueError("Invalid run count")
    if metadata["api"] != "28" or metadata["kind"] not in ("emulator", "hardware"):
        raise ValueError("Invalid API/kind")
    rows = []
    for phase in ("idle60", "idle300"):
        for i in range(1, count + 1):
            p = root / "memory" / f"{phase}-{i}.txt"
            if not p.exists():
                raise ValueError(f"Missing memory sample: {p}")
            rows.append({"phase": phase, "run": i, **parse_mem(p.read_text(errors="replace"))})
    keys = list(rows[0].keys())
    with (root / "memory-summary.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)
    starts = []
    for phase in ("cold", "warm-return"):
        for i in range(1, count + 1):
            p = root / "startup" / f"{phase}-{i}.txt"
            starts.append({"phase": phase, "run": i, "total_ms": parse_start(p.read_text(errors="replace")) if p.exists() else None})
    with (root / "startup-summary.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["phase", "run", "total_ms"])
        writer.writeheader()
        writer.writerows(starts)
    frames = {}
    for p in sorted((root / "frames").glob("*.txt")):
        frames.update(parse_frames(p.read_text(errors="replace")))
    with (root / "frame-summary.csv").open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["intended_vsync_ns", "frame_completed_ns", "duration_ms"])
        for (start, end), ms in sorted(frames.items()):
            writer.writerow([start, end, ms])
    lines = [f"installed_apk_sha256_verification={'MATCH' if installed != 'UNAVAILABLE' else 'UNAVAILABLE'}",
             "HOME-switch is observational: inspect home-foreground, home-return, and PID files.",
             "PSS is proportional-set-size KiB; heap_alloc_kb is allocated heap KiB, NOT PSS.",
             "Startup TotalTime is ActivityManager timing, NOT first frame or visual readiness.",
             "Frames deduplicated by (intended_vsync, frame_completed) across snapshots."]
    incomplete = False
    for phase in ("idle60", "idle300"):
        for key in keys[2:]:
            values = [r[key] for r in rows if r["phase"] == phase and r[key] is not None]
            s = stats(values)
            if s["n"] != count:
                incomplete = True
            lines.append(f"{phase} {key}: {s} expected_n={count}")
    for phase in ("cold", "warm-return"):
        values = [r["total_ms"] for r in starts if r["phase"] == phase and r["total_ms"] is not None]
        if len(values) != count:
            incomplete = True
        lines.append(f"{phase} total_ms: {stats(values)} expected_n={count}")
    lines.append(f"deduplicated frame duration ms: {stats(list(frames.values()))}")
    lines.append("GC/ANR/OOM/LMK: inspect phase-correlated raw logcat; no unreliable exact count asserted.")
    lines.append(f"completeness={'FAIL' if incomplete else 'PASS'}")
    (root / "summary.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    if incomplete:
        raise ValueError("Missing metrics or startup samples; see summary.txt")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 summarize.py evidence-dir")
    main(sys.argv[1])

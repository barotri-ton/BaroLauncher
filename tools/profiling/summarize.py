#!/usr/bin/env python3
"""Summarize Android 9 dumpsys evidence without discarding raw files."""
import csv
import re
import statistics
import sys
from pathlib import Path

def extract(text, patterns):
    for pattern in patterns:
        match = re.search(pattern, text, re.I | re.M)
        if match:
            return int(match.group(1).replace(",", ""))
    return None

def describe(values):
    values = [x for x in values if x is not None]
    return {"n": len(values), "median": statistics.median(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None}

def parse_mem(path):
    text = path.read_text(errors="replace")
    return {
        "pss_kb": extract(text, [r"^\s*TOTAL\s+([\d,]+)\s", r"^\s*TOTAL PSS:\s*([\d,]+)"]),
        "java_heap_kb": extract(text, [r"^\s*Java Heap:\s*([\d,]+)", r"^\s*Dalvik Heap\s+([\d,]+)"]),
        "native_heap_kb": extract(text, [r"^\s*Native Heap\s+([\d,]+)"]),
        "graphics_kb": extract(text, [r"^\s*Graphics:\s*([\d,]+)", r"^\s*Graphics\s+([\d,]+)"]),
    }

def main(root):
    root = Path(root)
    if not (root / "manifest.txt").exists():
        raise SystemExit("Not a profiling evidence directory")
    records = []
    for p in sorted((root / "memory").glob("idle*.txt")):
        metric = parse_mem(p)
        records.append({"sample": p.name, **metric})
    with (root / "memory-summary.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["sample", "pss_kb", "java_heap_kb", "native_heap_kb", "graphics_kb"])
        writer.writeheader()
        writer.writerows(records)
    starts = []
    for p in sorted((root / "startup").glob("*.txt")):
        text = p.read_text(errors="replace")
        starts.append({"sample": p.name, "total_ms": extract(text, [r"^TotalTime:\s*(\d+)"]),
                       "wait_ms": extract(text, [r"^WaitTime:\s*(\d+)"])})
    with (root / "startup-summary.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["sample", "total_ms", "wait_ms"])
        writer.writeheader()
        writer.writerows(starts)
    frames = []
    for path in sorted((root / "frames").glob("*.txt")):
        raw = path.read_text(errors="replace")
        inside = False
        for line in raw.splitlines():
            if line.startswith("---PROFILEDATA---"):
                inside = not inside
                continue
            if not inside or line.startswith("Flags,"):
                continue
            parts = line.split(",")
            if len(parts) < 14:
                continue
            try:
                # Android gfxinfo framestats: intendedVsync index 1, frameCompleted index 13.
                flags = int(parts[0]); start = int(parts[1]); end = int(parts[13])
                if flags == 0 and end > start:
                    frames.append((end - start) / 1_000_000)
            except ValueError:
                continue
    logpath = root / "logs" / "logcat.txt"
    logs = logpath.read_text(errors="replace") if logpath.exists() else ""
    gc_events = sum(1 for line in logs.splitlines() if re.search(r"\b(?:concurrent|explicit|background|alloc|young)\s+(?:mark\s+)?(?:sweep|GC)|\bGC_FOR_|\bGC_CONCURRENT", line, re.I))
    with (root / "summary.txt").open("w") as f:
        f.write("EMULATOR/HARDWARE designation: see manifest.txt\n")
        f.write("Memory values: KiB; startup values: ms. Missing metrics remain blank.\n")
        f.write("Startup TotalTime is am start -W, NOT visual readiness or fully drawn.\n")
        for phase in ("idle60", "idle300"):
            subset = [r for r in records if r["sample"].startswith(phase)]
            for key in ("pss_kb", "java_heap_kb", "native_heap_kb", "graphics_kb"):
                f.write(f"{phase} {key}: {describe([r[key] for r in subset])}\n")
        for phase in ("cold", "warm"):
            f.write(f"{phase} TotalTime: {describe([r['total_ms'] for r in starts if r['sample'].startswith(phase)])}\n")
        f.write(f"gfxinfo frame duration ms (all captures): {describe(frames)}\n")
        f.write(f"Potential GC log lines (heuristic, not exact count): {gc_events}\n")
        f.write("Review raw logcat for ANR/OOM; frame data may be unavailable.\n")
    print((root / "summary.txt").read_text())

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 summarize.py evidence-dir")
    main(sys.argv[1])

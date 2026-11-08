#!/usr/bin/env python3
"""Fail closed on missing/inconsistent full baseline evidence. No hardware claims."""
import csv
import re
import sys
from datetime import datetime
from pathlib import Path

root = Path(sys.argv[1])
manifest = dict(line.split("=", 1) for line in (root / "manifest.txt").read_text().splitlines() if "=" in line)
assert manifest["runs"] == "5", "Not five runs"
assert manifest["short_idle_seconds"] == "60" and manifest["long_idle_seconds"] == "300", "Not full idle baseline"
assert manifest["api"] == "28", "Not Android 9 API 28"
assert re.fullmatch(r"[0-9a-f]{64}", manifest["apk_sha256"], re.I), "Bad APK SHA"
for name in ("home", "accessibility"):
    a = (root / "environment" / (name + "-before.txt")).read_bytes()
    b = (root / "environment" / (name + "-after.txt")).read_bytes()
    assert a == b, name + " changed during profiling"
for filename, expected, phases in (
    ("memory-summary.csv", 10, {"idle60", "idle300"}),
    ("startup-summary.csv", 10, {"cold", "warm-return"}),
):
    with (root / filename).open(newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == expected, filename + " row count mismatch"
    assert {r["phase"] for r in rows} == phases
    for phase in phases:
        assert sorted(int(r["run"]) for r in rows if r["phase"] == phase) == [1, 2, 3, 4, 5]
    for r in rows:
        assert all(v.strip() for v in r.values()), filename + " has missing value"
with (root / "frame-summary.csv").open(newline="") as f:
    frames = list(csv.DictReader(f))
assert len(frames) == len({(r["intended_vsync_ns"], r["frame_completed_ns"]) for r in frames}), "Duplicated frame rows"
assert "completeness=PASS" in (root / "summary.txt").read_text(), "Parser completeness failed"
with (root / "memory" / "index.csv").open(newline="") as f:
    samples = list(csv.DictReader(f))
assert len(samples) == 10, "Expected ten memory timestamps"
for i in range(1, 6):
    t = {}
    for r in samples:
        if r["sample"] == str(i):
            t[r["phase"]] = datetime.fromisoformat(r["utc"].replace("Z", "+00:00"))
    assert set(t) == {"idle60", "idle300"}
    elapsed = (t["idle300"] - t["idle60"]).total_seconds()
    assert elapsed >= 240, f"Cycle {i} idle60 to idle300 too short: {elapsed}"
    for name in ("cold", "home-return", "warm-return", "away", "home-foreground",
                 "home-return-foreground", "settings-foreground", "pid-before",
                 "pid-after-home", "pid-before-settings-return"):
        assert (root / "startup" / f"{name}-{i}.txt").exists(), f"Missing {name}-{i}"
    assert (root / "startup" / f"pid-before-{i}.txt").read_text().strip(), "Missing original PID"
    assert (root / "startup" / f"pid-after-home-{i}.txt").read_text().strip(), "Process not alive after HOME"
    assert (root / "startup" / f"pid-before-settings-return-{i}.txt").read_text().strip(), "Process not alive before Settings return"
    assert (root / "startup" / f"pid-before-{i}.txt").read_text().strip() == (root / "startup" / f"pid-after-home-{i}.txt").read_text().strip(), "PID changed across HOME"
    assert (root / "startup" / f"pid-before-{i}.txt").read_text().strip() == (root / "startup" / f"pid-before-settings-return-{i}.txt").read_text().strip(), "PID changed across Settings"
# New instrumentation is mandatory; no silent acceptance of absent artifacts.
for name in ("enabled-packages", "disabled-packages"):
    before = (root / "environment" / f"{name}-before.txt").read_bytes()
    after = (root / "environment" / f"{name}-after.txt").read_bytes()
    assert before == after, f"{name} changed"
with (root / "screenshots" / "index.csv").open(newline="") as f:
    screenshots = list(csv.DictReader(f))
assert {r["filename"] for r in screenshots} == {"launcher-home.png", "android-settings.png"}
for r in screenshots:
    datetime.fromisoformat(r["utc"].replace("Z", "+00:00"))
    png = (root / "screenshots" / r["filename"]).read_bytes()
    assert png.startswith(bytes.fromhex("89504e470d0a1a0a")) and len(png) > 100, "Invalid PNG"
with (root / "host" / "index.csv").open(newline="") as f:
    host_rows = list(csv.DictReader(f))
assert len(host_rows) == 10, "Expected host metrics before/after each cold launch"
for i in range(1, 6):
    assert {r["phase"] for r in host_rows if r["cycle"] == str(i)} == {"before-cold", "after-cold"}
    for phase in ("before-cold", "after-cold"):
        assert (root / "host" / f"cpu-{i}-{phase}.txt").stat().st_size > 0
        assert (root / "host" / f"pressure-{i}-{phase}.txt").stat().st_size > 0
    for prefix in ("gfx-reset-cold", "cold-launch-frames", "cold-launch-foreground", "activity-after-cold"):
        assert (root / "timeline" / f"{prefix}-{i}.txt").exists(), f"Missing {prefix}-{i}"
for r in host_rows:
    assert all(r.values()), "Incomplete host metrics"
    datetime.fromisoformat(r["utc"].replace("Z", "+00:00"))
# Each cycle must contain its own ordered lifecycle sequence, bounded by its cold markers.
# These milestones do not establish first visible frame or fully drawn.
for i in range(1, 6):
    log = (root / "timeline" / f"startup-logcat-{i}.txt").read_text()
    begin = re.findall(r"^.*BaroProfile: cycle=" + str(i) + r" phase=cold_begin.*$", log, re.M)
    end = re.findall(r"^.*BaroProfile: cycle=" + str(i) + r" phase=cold_end.*$", log, re.M)
    assert len(begin) == 1 and len(end) == 1, f"Cycle {i}: missing/duplicated phase markers"
    a = log.index(begin[0])
    b = log.index(end[0])
    assert a < b, f"Cycle {i}: reversed cold phase markers"
    segment = log[a:b]
    names = ("onCreate.begin", "onCreate.contentViewReady", "onResume.begin", "firstPreDraw")
    stamps = []
    for name in names:
        matches = re.findall(
            r"BaroStartup: " + re.escape(name) + r"[^\n]*?elapsedRealtimeNs=(\d+)",
            segment,
        )
        assert len(matches) == 1, f"Cycle {i}: expected one {name}, got {len(matches)}"
        stamps.append(int(matches[0]))
    assert stamps == sorted(stamps), f"Cycle {i}: lifecycle timestamp order invalid"
    assert stamps[-1] - stamps[0] < 30_000_000_000, f"Cycle {i}: lifecycle exceeds 30 seconds"
    for path in (
        root / "timeline" / f"device-uptime-before-{i}.txt",
        root / "timeline" / f"device-uptime-after-{i}.txt",
        root / "timeline" / f"process-pid-after-cold-{i}.txt",
        root / "host" / f"uptime-before-{i}.txt",
        root / "host" / f"uptime-after-{i}.txt",
    ):
        assert path.is_file() and path.stat().st_size > 0, f"Missing timing evidence: {path}"

assert (root / "logs" / "logcat.txt").exists()
print("PASS: five-cycle baseline evidence, CSV completeness, PID continuity, safety snapshots.")
print("MANUAL QA REQUIRED: actual foreground activity, HOME switched externally, network/toggles, visual readiness, frame comparability, phase-correlated logcat.")

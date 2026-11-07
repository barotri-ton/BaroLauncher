# BL-P0-02 — Android 9 Memory Profiling Harness

Parent: [#5](https://github.com/barotri-ton/BaroLauncher/issues/5); Development: [#6](https://github.com/barotri-ton/BaroLauncher/issues/6); QA: [#7](https://github.com/barotri-ton/BaroLauncher/issues/7).

## Safety and evidence scope
The harness never changes the default HOME app, accessibility services, installed system packages or factory launcher. It only force-stops **BaroLauncher** for cold starts. Do not use `install.sh` for profiling if you do not intend to grant permissions; use `adb install` instead. No app/UI framework changes are part of this PR.

Emulator API 28 evidence is **screening only**, not Movie Legend A7 Ultra release acceptance. A 1 GB Pixel 2 emulator is not equivalent to the projector's firmware, GPU or memory behavior.

## Requirements
Android SDK Platform Tools (adb), Python 3, Bash, an installed APK built using `./build.sh`, and an authorized Android 9 (API 28) device/emulator. Record network state, test feature toggles, APK hash, and whether HOME is set to BaroLauncher manually in the manifest. For installed APK hash use `adb shell pm path com.rampey.materialtv` and pull the base APK if permitted, then `sha256sum` locally; otherwise hash the exact installed build artifact and record that provenance.

## Emulator setup (local)
Install Android SDK command-line tools and emulator with a supported JDK. Example on Linux:

```bash
sdkmanager "platform-tools" "emulator" "platforms;android-28" "system-images;android-28;google_apis;x86_64"
avdmanager create avd -n baro-api28 -k "system-images;android-28;google_apis;x86_64" --device "pixel_2"
emulator -avd baro-api28 -memory 1024 -no-snapshot -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device
adb shell getprop ro.build.version.sdk  # must print 28
./build.sh
adb install build/BaroLauncher.apk
```

This example requires host virtualization support and an emulator image compatible with the host. The GitHub workflow `profiling-emulator.yml` provides a manually triggered API 28 smoke run; it is not a substitute for local hardware testing.

## Run
```bash
bash tools/profiling/profile.sh --kind emulator --serial emulator-5554 --runs 5 --settle 120 --apk build/BaroLauncher.apk --network on --toggles default --out evidence/profiling/emulator-baseline-01
python3 tools/profiling/summarize.py evidence/profiling/emulator-baseline-01
# For physical projector, use --kind hardware --serial <actual-adb-serial>
```

The default full protocol takes at least 25 minutes plus settling, warm returns and ADB overhead. `--quick` is **CI smoke only** and does not satisfy baseline acceptance. Raw evidence is written into environment/, startup/, memory/, frames/ and logs/. Summary CSV and median/min/max are generated; missing metrics are left blank rather than invented.

**Important measurement semantics and limitations:**
- Each run force-stops **only BaroLauncher**, cold-starts, waits 60 seconds, captures `idle60`, waits to 300 seconds from cold launch, then captures `idle300`. Each of the five 300-second samples belongs to an independent cold-start cycle. Exact wall-clock sampling has ADB overhead; see timestamps.
- The warm-return path starts Android Settings, then returns explicitly to BaroLauncher without force-stop. This is a **Settings-switch return**, not a HOME-button return; QA must separately validate HOME selection/return and video-visible readiness.
- `am start -W` is ActivityManager timing only, not first frame or fully drawn.
- PSS columns (`total_pss_kb`, `dalvik_pss_kb`, `native_pss_kb`, `graphics_pss_kb`) are **not** allocated heap. `java_heap_alloc_kb` and `native_heap_alloc_kb` are taken from Heap Alloc columns when available; missing values remain blank and cause a completeness failure.
- Frame records are deduplicated by intended-vsync/completion timestamp pair across overlapping gfxinfo snapshots. This prevents duplicate frame rows but does not guarantee comparable window durations.
- GC/ANR/OOM/LMK require phase-correlated inspection of raw logcat; no exact GC event count is claimed.
- `--apk` records SHA-256 of the **local build artifact** and labels its provenance; it does not prove the installed APK is byte-identical. Record installed APK hash separately when possible.
- `--network on|off` and `--toggles DESCRIPTION` are mandatory operator declarations; verify they match actual device state.
- CI `--quick --runs 2` is smoke only, not a valid five-run baseline.
- Focus sweeps, media, screensaver and 30-minute background remain separate manual QA scenarios.

## Reporting
Attach the raw evidence artifact (redacted) and record exact run, APK/versionCode, Git commit, network, installed apps, emulator vs hardware, median/range and any missing metrics. Never check sensitive logcat into the public repository. CI artifacts are retained for 14 days; archive reviewed evidence appropriately.

## Acceptance
- [ ] CI shell/Python syntax and APK build verified
- [ ] API 28 emulator smoke produces raw logs and summary
- [ ] Five full-run samples reviewed for comparability
- [ ] QA reproduces memory/startup and separate HOME-switch/focus workloads
- [ ] A7 Ultra hardware baseline (BLOCKED: device date unknown)

## QA re-validation protocol — full cloud baseline

The profiling workflow now requests **five independent cold-start cycles** with 60s and 300s samples, and a 120s initial settling period. It may take 30+ minutes. The workflow is a Google APIs phone-shaped API28 screening emulator, **not an Android TV firmware emulator** and not A7 Ultra hardware. The separate Android TV cloud UI workflow is tracked in PR #9. Do not combine those datasets.

Each cycle saves HOME keypress foreground evidence, HOME return activity timing, BaroLauncher PID before/after HOME, Settings foreground evidence and PID before Settings return. HOME may resolve back to BaroLauncher; this is an observational test, not proof of a true external HOME switch. QA must verify the actual foreground activity and process continuity from raw files.

Metadata includes local APK SHA-256 and an attempted installed base APK SHA-256 via ADB read; if unavailable, provenance is explicitly UNAVAILABLE. Network connectivity dump and airplane mode are recorded, but feature toggles remain operator-declared, not independently verified. APK hash mismatch is a hard error when installed hash is available. Exact frame readiness still needs synchronized visual evidence. Missing meminfo fields are errors, not imputed values.

Regression scope: HOME and accessibility defaults are compared before/after; D-pad/Back, app discovery/launch, offline, recovery and signing/upgrade require separate QA evidence. No source changes are made to stock launcher or HOME settings. Logcat may include personal data; redact before publishing.

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
adb install build/material-tv-launcher.apk
```

This example requires host virtualization support and an emulator image compatible with the host. The GitHub workflow `profiling-emulator.yml` provides a manually triggered API 28 smoke run; it is not a substitute for local hardware testing.

## Run
```bash
bash tools/profiling/profile.sh --kind emulator --serial emulator-5554 --runs 5 --settle 120 --out evidence/profiling/emulator-baseline-01
python3 tools/profiling/summarize.py evidence/profiling/emulator-baseline-01
# For physical projector, use --kind hardware --serial <actual-adb-serial>
```

The default full protocol takes approximately 30 minutes plus ADB overhead. `--quick` is **CI smoke only** and does not satisfy baseline acceptance. Raw evidence is written into environment/, startup/, memory/, frames/ and logs/. Summary CSV and median/min/max are generated; missing metrics are left blank rather than invented.

**Important measurement caveats:**
- Cold runs force-stop only `com.rampey.materialtv`. Warm runs call `am start -W` again without force-stop, so these are **same-process relaunch** samples, not verified HOME-switch returns. QA must independently perform and document HOME-switch warm returns.
- `am start -W` reports ActivityManager launch timing, **not first visible frame or fully drawn**. Use screen recording/trace on target hardware for visual readiness.
- `idle60` is sampled 60 s after each cold launch. `idle300` is sampled after the first run and at later intervals without a fresh cold start; these are **not five independent 5-minute post-launch samples**. For strict 5-minute post-launch measurements, rerun individual cold sessions with a controlled 300-second wait.
- Java/native/graphics fields depend on the device's `dumpsys meminfo` format. Review raw outputs before using summaries.
- Frame duration is derived from `gfxinfo framestats` timestamps if supported; repeated snapshots can include overlapping frames, so aggregate frame counts are **not independent samples**.
- GC count is a log-pattern heuristic; inspect raw logcat for GC, OOM, ANR and skipped frames.
- Focus sweep, app return, media, screensaver and 30-minute background workloads remain **manual QA scenarios**. Record them as distinct runs with the same environment metadata; do not mislabel the idle harness as full scenario coverage.

## Reporting
Attach the raw evidence artifact (redacted) and record exact run, APK/versionCode, Git commit, network, installed apps, emulator vs hardware, median/range and any missing metrics. Never check sensitive logcat into the public repository. CI artifacts are retained for 14 days; archive reviewed evidence appropriately.

## Acceptance
- [ ] CI shell/Python syntax and APK build verified
- [ ] API 28 emulator smoke produces raw logs and summary
- [ ] Five full-run samples reviewed for comparability
- [ ] QA reproduces memory/startup and separate HOME-switch/focus workloads
- [ ] A7 Ultra hardware baseline (BLOCKED: device date unknown)

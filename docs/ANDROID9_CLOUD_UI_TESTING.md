# Android 9 cloud UI smoke (no local PC required)

Run on GitHub Actions using an ephemeral Android 9 (API 28) Google APIs emulator configured with 1024 MB RAM. No projector or local Android Studio required.

## Run
1. Review and merge the workflow PR after QA.
2. In GitHub → Actions → **Android 9 cloud UI smoke** → Run workflow on `main`. PRs changing app code or the smoke workflow also trigger it.
3. Open the workflow run → Artifacts → `android9-cloud-ui-evidence` and download ZIP on a browser/phone.
4. Inspect screenshots, `results.csv`, startup, meminfo, gfxinfo, logcat and HOME/accessibility before/after files.

## What is checked
- API 28, installed package, successful Activity launch and process present.
- Injects D-pad directions, CENTER and BACK, saving screenshots.
- Navigates to Settings and returns explicitly to BaroLauncher.
- Compares HOME handler and accessibility service settings before/after.
- Captures raw memory and graphics evidence.

## Limitations
- Key injection + screenshots proves events were sent, **not** that focus/selection is correct. QA must visually review screenshots; tests intentionally mark key steps OBSERVED, not PASS.
- The emulator is a generic phone-shaped Pixel 2 Google APIs image, **not Android TV**. TV-specific focus/launcher compatibility remains unverified.
- It does not press HOME, set BaroLauncher as default launcher, or disable/uninstall stock launcher.
- Emulator memory and GPU metrics do not certify Movie Legend A7 Ultra performance.
- A CI success indicates only smoke checks passed; not a full UI regression.
- Raw logcat may contain sensitive data; redact before publishing outside trusted Actions artifacts.
- GitHub Actions artifacts expire after 14 days.

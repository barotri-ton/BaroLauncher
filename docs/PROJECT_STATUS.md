# BaroLauncher — Project Status

Last synchronized: 2026-11-07. This file is the shared handoff between the Product/Architecture and Development/Delivery conversations.

## Approved decisions
- Target: Movie Legend A7 Ultra projector, Android 9 (API 28), 1 GB RAM.
- Retain the existing Java/Android SDK UI framework; optimize only after profiling.
- MVP scope: P0 first. Distribution: sideloaded APK.
- Navigation: D-pad first, usable without touchscreen.
- Offline-first core home/app-launching experience.
- Preserve the original stock launcher and a safe path back to it; never automatically disable/uninstall it.
- Product versions follow Release / Service Pack / Patch; see [VERSIONING.md](VERSIONING.md).

## Verified implementation status
- Original launcher repository migrated to `barotri-ton/BaroLauncher`; original main source files preserved.
- GitHub Actions builds Android 9 APK; run 42348325787 succeeded and published an APK.
- Version metadata is stored in root `VERSION` and read by `build.sh`; current configured version at synchronization: `1.0.0.0-beta.1`, versionCode 185.
- GitHub Actions release publishing was updated to refresh the existing `android9-latest` release.
- No on-device profiling or projector acceptance test has been completed in this workflow.

## MVP P0 acceptance checklist (not yet verified on hardware)
- [ ] Cold launch and HOME navigation work on Android 9 with remote only.
- [ ] Every interactive control is reachable and visibly focused via D-pad; Back navigation is predictable.
- [ ] Installed apps list loads, launches selected apps and returns to launcher.
- [ ] Essential home functionality works with Wi-Fi disabled.
- [ ] Stock launcher remains installed/enabled; user can select or restore it without deleting BaroLauncher.
- [ ] APK sideload/install/update is verified with a consistent signing certificate and increasing versionCode.
- [ ] Capture cold-start, warm-start, PSS, D-pad latency, and crash/ANR evidence on target device.

## Performance targets (provisional, subject to baseline profiling)
- Idle PSS < 60 MB.
- Cold launch < 1.5 seconds.
- D-pad interaction response < 100 ms.
- APK < 10 MB.
These are proposed targets, NOT validated performance or approved hard gates.

## Implementation backlog
| ID | Priority | Task | Exit criteria | Status |
| --- | --- | --- | --- | --- |
| BL-P0-01 | P0 | Verify build and repeatable APK artifact/release | CI build + downloadable APK; release update repeatable | CI built; release flow to monitor |
| BL-P0-02 | P0 | Establish baseline profiling harness | Document reproducible ADB commands, capture startup/PSS/latency | TODO |
| BL-P0-03 | P0 | Audit D-pad focus and Back behavior | Manual navigation matrix and no unreachable primary controls | TODO |
| BL-P0-04 | P0 | Audit launcher fallback safety | Stock launcher never removed/disabled; documented recovery | TODO |
| BL-P0-05 | P0 | Optimize proven RAM/startup hotspots | Before/after evidence and no functional regressions | TODO |
| BL-P0-06 | P0 | Validate APK upgrade/signing | VersionCode monotonic, certificate consistency | TODO |
| BL-P0-07 | P0 | Execute hardware acceptance | Target device results and reproducible issue reports | BLOCKED: device unavailable |
| BL-P1-01 | P1 | Advanced customization/optional content | After P0 validation | DEFERRED |

## Session handoff protocol
1. Read this file, `docs/VERSIONING.md`, and current GitHub Actions before starting work.
2. Develop on a dedicated feature branch when multiple sessions may edit the same files.
3. After changes, record commit/PR, CI result, verification scope, known risks, and next steps here.
4. Distinguish code compilation from hardware verification. Never mark hardware criteria passed without device evidence.
5. Do not alter or remove the original launcher during tests.

## Current next action
Implement BL-P0-02 profiling instructions and BL-P0-04 recovery audit before attempting speculative UI rewrites.

# BaroLauncher MVP P0 Test Cases
Precondition for hardware cases: stock launcher installed/enabled, recovery path available, device is Android 9 with physical D-pad remote. Never uninstall or disable stock launcher. Each case records evidence and PASS/FAIL/BLOCKED/NOT_RUN.

| ID | Priority | Procedure | Expected |
| --- | --- | --- | --- |
| TC-001 | P0 | Inspect APK manifest/minSdk, package, launcher HOME intent | Compatible with API 28; launcher discoverable; package identity stable |
| TC-002 | P0 | Sideload APK on clean Android 9 environment | Install succeeds without destructive stock-launcher changes |
| TC-003 | P0 | Cold start launcher using remote only | Home renders without crash/ANR; initial focus visible |
| TC-004 | P0 | Navigate all interactive controls with Up/Down/Left/Right/Select | All P0 controls reachable, focus visible, no traps |
| TC-005 | P0 | Press Back from home, app list, dialogs, nested views | Predictable navigation; no unintended blank screen/exit loop |
| TC-006 | P0 | Open installed apps list, scroll to first/middle/last item | Apps shown correctly; focus and scrolling remain usable |
| TC-007 | P0 | Select multiple installed apps with remote | Correct app launches; no wrong-app launch |
| TC-008 | P0 | Return from launched app using HOME/Back as supported | User can reliably return to launcher |
| TC-009 | P0 | Open HOME chooser/settings and select stock launcher | Stock launcher remains available and usable |
| TC-010 | P0 | Reboot, recover stock HOME and uninstall BaroLauncher normally | Device remains operable; no soft-brick or forced dependency |
| TC-011 | P0 | Disable Wi-Fi, cold launch, navigate and launch locally installed app | Core launcher remains usable offline |
| TC-012 | P0 | Measure cold/warm start and idle PSS over repeat runs | Measurements recorded with method, samples and distribution |
| TC-013 | P0 | Measure D-pad response, stress navigation and inspect logcat for crash/ANR/LMK | Evidence captured; no crash/ANR in defined run |
| TC-014 | P0 | Install previous signed APK then upgrade to candidate | Same signing identity, strictly higher versionCode, update succeeds and settings persist where applicable |
| TC-015 | P0 | Inspect VERSION and release tag/APK checksum | Correct release metadata; immutable version tag for validated build |
| TC-016 | P0 | Launch/return repeatedly under realistic 1 GB RAM pressure | No persistent unusable state; launcher recovers after process recreation |

Execution details: record actual steps and observations per environment, including installed-app samples and exact recovery procedure. Never assert pass without evidence.

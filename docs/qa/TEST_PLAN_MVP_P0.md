# MVP P0 Test Plan
Owner: QA Lead | Status: Planned | Date: 2026-11-07

## Scope and execution order
1. Static checks: package identity, minSdk <=28 and Android 9 installability, versionName/versionCode, signing, APK size, launcher intent/filter and permissions.
2. Android 9 emulator smoke: startup, D-pad focus and Back, installed apps, app return, offline behavior, repeated launch/rotation if supported.
3. Projector hardware: remote-only navigation, HOME chooser, stock launcher fallback, cold boot, repeated launch, low-memory behavior, install/update, profiling.
4. Release candidate regression and signoff.

## Environment matrix
| Environment | Purpose | Acceptance authority |
| --- | --- | --- |
| Android 9 API 28 emulator, D-pad/key events | Early functional smoke | No hardware signoff |
| Movie Legend A7 Ultra, Android 9, 1 GB RAM, physical remote | Full P0 + performance | Required |
| Upgrade from previous signed BaroLauncher APK | Signature/version continuity | Required |

## Traceability
BL-P0-01: TC-001, TC-002, TC-014
BL-P0-02: TC-011, TC-012, TC-013
BL-P0-03: TC-003, TC-004, TC-005
BL-P0-04: TC-009, TC-010
BL-P0-05: TC-011, TC-012, TC-013
BL-P0-06: TC-014, TC-015
BL-P0-07: all physical-device cases

## Execution record
Build/version: TBD; commit: TBD; hardware: unavailable; results: NOT_RUN unless actual evidence attached. QA report on each candidate should include counts by status, blocker list, open risks, gate decision and links to CI/artifacts.

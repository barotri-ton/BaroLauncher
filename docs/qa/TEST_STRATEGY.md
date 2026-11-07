# BaroLauncher QA Test Strategy — MVP P0
Status: Draft for review | 2026-11-07 | Owner: QA Lead

## References and constraints
Source of truth: docs/PROJECT_STATUS.md and docs/VERSIONING.md. Android 9/API 28, Movie Legend A7 Ultra, 1 GB RAM; Java/Android SDK existing UI, APK sideload, offline-first, D-pad first. Stock launcher MUST remain installed and enabled. ADR/FRD locations must be confirmed with Product/Development before traceability signoff.

## Quality approach
Risk-based validation: P0 safety/recovery, remote-only navigation, installed app discovery/launch/return, offline functionality, install/update/signing and stability. P1 customization deferred.
Test levels: static manifest/build checks; Android 9 emulator functional smoke; physical-device end-to-end; regression; performance profiling; release verification. Emulator evidence is NOT device acceptance.
Evidence per run: versionName/versionCode, commit SHA, APK SHA256, signing fingerprint, OS/API, device/RAM, test ID, steps, expected/actual, logs/screenshots/video, tester/date. Status: PASS, FAIL, BLOCKED, NOT_RUN, N/A (reason).
Severity: blocker = recovery loss/boot unusable; critical = primary launcher workflow broken; major = degraded P0 flow; minor = cosmetic.
Defect workflow: reproduce, record environment/evidence, file GitHub issue, link test ID and development owner; QA never changes functional source without Development agreement.

## Entry criteria
Requirements and expected behavior reviewed; CI APK available; test environment identified; stock launcher recovery procedure documented; stable test build/signature.
## Exit criteria
All P0 cases executed on target hardware and passed; zero open blocker/critical defects; recovery and upgrade explicitly passed; regression green; profiling captured and evaluated; remaining major defects accepted in writing by Product + QA + Development; immutable tag and APK checksum/signature recorded. Provisional performance targets are NOT hard gates until baselined and approved.
## Risks
No physical device currently available; hardware acceptance blocked. Focus/Back behavior may differ on projector remote vs emulator. 1 GB RAM increases LMK/ANR risk. Unknown OEM HOME chooser and fallback behavior.

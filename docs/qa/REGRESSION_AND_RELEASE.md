# Regression Checklist and Release Acceptance
Status: Draft | Owner: QA Lead

## Regression checklist
- [ ] Clean sideload and launcher visibility (TC-001/002)
- [ ] Cold start and visible focus (TC-003)
- [ ] D-pad full traversal and selection (TC-004)
- [ ] Back behavior across views/dialogs (TC-005)
- [ ] App listing, scrolling, app launch and return (TC-006/007/008)
- [ ] Offline home/app launch (TC-011)
- [ ] Stock launcher selectable, reboot recovery and safe uninstall (TC-009/010)
- [ ] Android 9 API 28 on target projector (all hardware P0)
- [ ] 1 GB RAM repeated launches/process recreation (TC-016)
- [ ] Startup/PSS/D-pad latency/crash/ANR evidence (TC-012/013)
- [ ] Signed upgrade/versionCode monotonic and settings preservation (TC-014)
- [ ] Version/release metadata and APK checksum (TC-015)

## Release acceptance criteria
Mandatory: hardware P0 all PASS; zero open blocker/critical; fallback and upgrade PASS; CI green; reproducible APK with SHA256 and signing fingerprint; release candidate version and commit documented; Product, QA and Development signoff.
Performance targets from PROJECT_STATUS.md are provisional: idle PSS <60 MB, cold start <1.5s, D-pad <100ms, APK <10 MB. Capture baseline before proposing enforceable thresholds; deviations require review, not automatic rejection while targets remain provisional.
Versioning: MAJOR.RELEASE.SERVICE_PACK.PATCH[-CHANNEL.N], VERSION_NAME/VERSION_CODE from root VERSION; versionCode strictly increases; android9-latest is moving, never use as immutable release evidence. Release candidate and GA need distinct immutable tags once validated.
Decision vocabulary: GO / NO-GO / CONDITIONAL (only for explicitly approved non-critical exceptions). Current status: NO-GO for GA because hardware validation is BLOCKED.

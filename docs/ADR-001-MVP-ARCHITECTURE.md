# ADR-001 — Existing Android UI and P0-first delivery

- Status: Accepted
- Date: 2026-11-07

## Context
BaroLauncher targets the Movie Legend A7 Ultra projector running Android 9 (API 28) with only 1 GB RAM. Remote D-pad navigation, offline operation and recovery to the original launcher are critical. The existing application uses Java and the Android SDK build tools without Gradle.

## Decision
1. Keep the current UI technology and package ID; do not rewrite the UI before profiling.
2. Limit the initial MVP to P0 app discovery/launching, reliable D-pad focus, startup stability, offline use and safe fallback.
3. Distribute APKs by sideload. Keep stock HOME app installed and enabled; never silently replace, disable or uninstall it.
4. Treat optional weather, media, personalization and animations as candidates for lazy loading or deferral, not mandatory startup dependencies.
5. Measure startup latency, memory and interaction responsiveness before and after each performance optimization.
6. Use version metadata in `VERSION` and the release/service-pack policy in `VERSIONING.md`.

## Consequences
- Less migration risk and faster iteration on the existing launcher.
- Hardware acceptance remains blocked until access to a compatible device.
- Emulator and CI checks are useful but cannot establish actual 1 GB projector performance.
- Performance targets in PROJECT_STATUS.md are provisional until baselined.

## Revisit when
Profiling proves the current UI implementation cannot meet P0 usability or memory goals without structural changes.

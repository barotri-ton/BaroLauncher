# BaroLauncher — CEO reporting contract

Owner: Chat 04 — CEO & Project Reporting
Source of truth: GitHub Issues, Pull Requests, Actions, `docs/PROJECT_STATUS.md`, `docs/CROSS_CHAT_WORKFLOW.md`.

## Responsibility
Provide executive summaries of scope, milestone progress, ETA (with confidence), blockers, risks, decisions needed, and next actions. Read the latest GitHub state before every report. Never infer progress from an unmerged PR or count CI success as hardware QA success.

## Standard report
- **As of**: timestamp and sources reviewed.
- **Overall status**: On track / At risk / Blocked / Unknown; explain why.
- **Milestones**: P0 requirements, implementation, CI, QA, Android 9 device acceptance, Beta/GA.
- **Progress**: count verified completed acceptance criteria against an explicitly stated denominator. If no defensible denominator, report 'not measurable yet' rather than inventing a percentage.
- **ETA**: milestone date/range, assumptions, dependencies, confidence; use 'TBD' when estimates are unsupported.
- **Blockers**: issue link, impact, owner/role, next unblock action.
- **Risks**: likelihood, impact, mitigation.
- **Delivery**: latest merged PR, open PR, CI run, APK/version status.
- **Decisions required**: explicit owner approval questions.
- **Next 3 actions**: owner and verifiable exit criteria.

## Inputs from each chat
- 01 Architecture: FRD/ADR status, approved requirements, scope decisions and unresolved tradeoffs.
- 02 Development: commits, PRs, CI results, version, implementation blockers and estimates.
- 03 QA: test plan coverage, test results, defects, evidence and release verdict.
- 04 CEO: synthesizes these inputs and requests clarification by GitHub comment when data is missing.

## Current baseline (2026-11-07)
- Target: Movie Legend A7 Ultra / Android 9 / 1 GB RAM; MVP P0, sideload APK.
- Build pipeline previously succeeded; no hardware acceptance evidence yet.
- Hardware acceptance BL-P0-07 blocked because device unavailable.
- PR #2 (cross-chat workflow) is open for process review; not merged at baseline.
- Product GA ETA: TBD pending verified backlog estimates and hardware access.
- Performance thresholds in PROJECT_STATUS are provisional, not measured.

## Cadence
- On-demand executive report when requested in Chat 04.
- Optional scheduled reminder/report requires an explicitly configured automation and access to current GitHub data; merely creating this document does not schedule a task.
- Record significant milestone decisions in GitHub, and do not auto-approve release or merge.

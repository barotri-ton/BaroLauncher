# Cross-chat coordination — BaroLauncher

## Important limitation
ChatGPT conversations cannot directly exchange messages, autonomously wake one another, or guarantee real-time cross-chat discussions. GitHub Issues, PRs and tracked documents are the durable shared communication channel. Automated GitHub Actions can post status summaries and run checks, but do not constitute independent AI agents.

## Roles and contract
- **01 Product & Architecture**: create or update an `[ARCH]` Issue with FRD/ADR reference, priority and measurable acceptance criteria. Review scope changes.
- **02 Development & CI/CD**: work on `feature/*` or `fix/*` branches; reference the Issue in PR; attach CI results and APK artifacts; request QA verification.
- **03 QA & Testing**: review PR, publish test verdict and evidence; file `[QA][BUG]` Issues for defects; block release when acceptance fails.

## GitHub state machine
`Proposed → Ready for Dev → In Development → Ready for QA → QA Passed / QA Failed → Approved → Merged → Released`.

These states are **manual decisions** recorded in Issues/PR comments. Automation does not fabricate QA sign-off or merge.

## Automated checks
- Push / PR: Android 9 APK build and artifact from `build-apk.yml`.
- PR: handoff checklist validation from `handoff-check.yml`.
- PR handoff validation currently checks section headings only; it does not verify substantive fields, QA verdict or approval. Automatic bot comments are **not implemented**.

## Session start prompt
"Read `docs/PROJECT_STATUS.md`, `docs/CROSS_CHAT_WORKFLOW.md`, current open Issues and PRs in `barotri-ton/BaroLauncher`. Identify your role's ready work and avoid editing files owned by another active PR."

## Session end prompt
"Update your GitHub Issue/PR with commits, CI run, tested vs untested, risks and next action. Update `docs/PROJECT_STATUS.md` through a PR if a milestone or accepted decision changed."

## Safety gates
- Never uninstall or disable the factory launcher.
- Do not mark emulator-only results as real-device acceptance.
- Never automatically merge or publish GA without human approval.
- Do not let different sessions directly push conflicting changes to `main`.

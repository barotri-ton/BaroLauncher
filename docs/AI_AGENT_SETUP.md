# Level 2 — AI Coding Agent setup (pilot)

Status: proposed in PR; **not live until merged and configured**.

## Architecture
- Coding agent: OpenAI Codex CLI (`@openai/codex`).
- Runner: ephemeral GitHub-hosted `ubuntu-latest`, 30-minute timeout, serialized per Issue.
- Trigger: manual `workflow_dispatch` with an existing reviewed Issue number.
- Output: `agent/issue-<number>-run-<run_id>` branch and **draft PR**.
- Guardrail: only `src/`, `res/`, `assets/` may be committed; no automatic merge or release.
- AI runner is not the four ChatGPT conversations. GitHub Issues/PRs carry the handoff.

## Required setup (repository owner)
1. Review and merge the configuration PR.
2. In GitHub Settings → Environments create **`ai-agent`**, set required reviewer (owner) and restrict deployment branches if appropriate. This is the human authorization gate for spending API credits and executing AI code.
3. In that environment add secret **`OPENAI_API_KEY`** from an account with API billing and a limited project budget. ChatGPT Plus subscription does not itself provide API credits.
4. Create a dedicated fine-grained GitHub PAT, restricted to **only this repository**, with Contents: Read/Write, Pull requests: Read/Write, Issues: Read. Store as environment secret **`BARO_AGENT_GH_TOKEN`**. Prefer a GitHub App installation token later for tighter control and expiration. Do not paste either secret into Issues, chats, PRs or source code.
5. Check repository Actions policy and permission to create PRs. Use a low-risk, explicitly approved Issue for first run.
6. Open Actions → **AI Agent - Implement GitHub Issue** → Run workflow → enter Issue number; approve the `ai-agent` environment deployment.
7. Inspect logs, changed-file allowlist, draft PR and subsequent Android build CI. Do not merge without Architecture/QA review.

## Security notes
- The issue body is untrusted. An AI prompt can still be vulnerable to prompt injection; instructions and path restrictions reduce but do not eliminate risk.
- GitHub-hosted runner can access external networks and the agent process receives an API key. Do not run unreviewed Issues. Do not place additional production secrets in this environment.
- The file allowlist is enforced **after** agent execution; it limits pushed changes, not every possible runtime action. Stronger sandbox/network isolation is a future hardening task.
- The PAT is used to push/open PR because changes made with the default `GITHUB_TOKEN` may not trigger follow-on workflows. Verify actual CI behavior on the pilot PR.
- Agent output is not trusted; inspect for security, focus navigation, startup/memory regressions and stock-launcher safety.
- The runner does not self-schedule, select Issues autonomously or keep working after a failed run. Automatic label-triggered dispatch should only be added after pilot and approval design.
- The current pilot has no agent-specific build step before opening a PR. Existing PR CI is the build gate.

## Pilot acceptance criteria
- [ ] Environment reviewer approval required.
- [ ] Missing secrets fail safely.
- [ ] Only reviewed Issue can be manually dispatched.
- [ ] Agent produces isolated branch and draft PR or reports no changes.
- [ ] Files outside allowlist cannot be pushed.
- [ ] PR CI runs and Android 9 APK builds.
- [ ] QA reviews generated code and regression risks.
- [ ] No automatic merge, GA release or stock launcher removal.

## Rollback
Disable the workflow in GitHub Actions, revoke the PAT and API key, close generated PRs, and delete `agent/*` branches. Existing `main` remains unchanged until manually merged.

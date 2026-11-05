# Project notes

## 2026-10-05 — Local AI investigation for payout approvals

- Added local Qwen via Ollama to draft advisory briefs for manual approval runs. Model calls are bounded to 14 seconds at the HTTP layer and Temporal does not retry them; failures fall back to human review.
- The model can choose zero, one, or two read-only synthetic lookups. Each lookup is a Temporal Activity. Empty review facts skip investigation deterministically, and `MarkInvestigationSkipped` records a no-lookup decision in Event History.
- Scenario controls now accept synthetic review facts and select between two synthetic customer records. Successful payout defaults to “typical customer behavior”; the other scenarios default to “new recipient” and “unusual amount for this customer.”
- Live Qwen runs initially made unsupported claims about amount units and recipient verification. The brief now keeps numeric amounts in typed UI fields, validates referenced facts, and uses fixed notes for the two default flags. Synthetic lookup data cannot establish fraud, and the recommendation never approves or rejects a payout.
- The local build and approval/replay tests passed during development. Live manual approval runs exercised both the two-lookup path and the no-lookup timeline marker. These runs were functional checks, not latency benchmarks.
- Hosted model selection remains future work. `DEMO_AI_MODEL` currently changes the local Ollama model without a UI toggle.

## 2026-10-06 — AI brief on low-value demo payouts

- Startup now displays Compose progress and an elapsed-time update every five seconds while Docker is quiet. A 20-second Docker Engine readiness check stops an unresponsive startup rather than reporting the containers as up. A local daemon probe timed out; a later live `make start` completed with the new progress output.
- Two manual $75 runs had `aiBriefEnabled=true` and review facts but no AI Activities because the workflow entered the AI branch only above the $500 human-approval threshold.
- Manually started demo payouts now draft the AI brief after FX validation at any amount. Payouts below the threshold still skip human approval; load-simulator runs still skip AI. Existing histories without the version marker replay on their original path.
- Unit tests passed for a successful low-value payout, a permanent rail rejection after investigation, and the existing replay suite. Live runs `payout-successful-po-01a111aa0d7874f9` and `payout-permanent-po-01a111aa0d7874fa` recorded ChooseInvestigation and DraftAiBrief before their respective completion and rail rejection, with available Qwen briefs.

## 2026-10-06 — Typical low-value payout recommendation

- The successful $75 live run completed without human approval, but Qwen chose both customer lookups and returned `ROUTINE_REVIEW` for its sole “typical customer behavior” fact. That recommendation contradicted the intended low-risk demo case.
- The clean baseline now deterministically skips investigation and constrains the advisory brief to `NO_REVIEW_NEEDED` with no review items. Qwen still drafts the summary; the fixed $500 approval threshold continues to control whether the payout waits for a human. This exact-fact rule is deliberately narrow and does not establish whether a real payout is fraudulent.
- Targeted activity and workflow tests passed. Fresh synthetic run `payout-successful-po-01a111b1fcd673bb` completed with an available Qwen brief, `NO_REVIEW_NEEDED`, no review items, and a Temporal history containing ChooseInvestigation, MarkInvestigationSkipped, and DraftAiBrief without either customer lookup.
- The original successful run was saved as a 52-event replay fixture. The replay suite passed with it, confirming that a pre-fix low-value run with `aiBriefEnabled=true` does not gain new commands on replay.

## 2026-10-06 — Transfer amount in the demo UI

- Scenario controls previously exposed minor units, so the successful payout showed `7500` for $75.00. The UI now accepts USD dollars and cents, converts them to integer minor units for the existing API, and shows the formatted amount beside the input, on the start button, and in live status.
- The input rejects zero, negative values, excess decimal places, and amounts outside JavaScript's safe integer range before starting a payout. The API and stored workflow amounts remain in minor units.
- `node --check` passed. After restarting the local demo, the browser showed `$75.00` in the amount hint, start button, and live status; editing the input to `75.25` updated the hint and button to `$75.25`, then the default was restored.

## 2026-10-07 — Cloud registry adapter (in progress)

- Added a multi-stage JDK 21 Docker build for the Spring API and its supervised worker in one container. This preserves the shared file-backed scenario configuration required for failure injection, but that file is ephemeral across pod replacement.
- Added an OpenAI Chat Completions path with strict JSON schemas for investigation choice and brief drafting. Local Ollama/Qwen remains the default. The choice and draft HTTP timeouts fit their respective 15-second and 30-second Temporal Activity limits. A local mock-server test passed; a live OpenAI request has not been verified.
- Cloud mode replaces the same-origin Temporal Web iframe with a Temporal Cloud link and hides the local Grafana pane. The registry manifest declares all eleven custom search attributes, the platform Temporal proxy, and a project-scoped `OPENAI_API_KEY` reference.
- The registry validator accepted the manifest. Deployment remains unverified because the `temporal-sa/temporal-demo-payments` source repository and the project-scoped secret must be provisioned; the current source is Mark's repository. The registry only supports project-scoped secret injection, so the shared OpenAI credential must be copied or bridged to `tmprl-dem-cld/payout-orchestration/openai-credentials` by an authorized operator.
- GitHub rejected transfer of `Shy/temporal-demo-payments` to `temporal-sa` with HTTP 422: the current account lacks permission to create public repositories in that organization. An organization owner must grant the permission or arrange the transfer before the registry source can build.

# Conversation summary (this working session)

Written by the assistant from the session. The chat text itself (without tool output) is in `session-history/`. Dates are 2026-10-23. For where AI helped and was wrong, see `ai-usage-notes.md`; for failures, `issues-log.md`.

## What the author asked for, in order
1. Read the four earlier AI conversations (Kimi, Gemini, Claude, Z.ai) in plan mode, show the plan, and record in the README the decisions taken from all four.
2. Validate against the assignment PDF (install `pdftoppm`, always validate, no contradictions).
3. Build in steps with validation at every layer (architecture, worker, integration, the running app via Playwright, load via Locust), all evidence saved in `results/`.
4. Create `.env` (blocked by a hook; made optional by giving every value a default and documenting `.env.example`).
5. Step 5: SQS, DynamoDB adapters and LocalStack integration tests.
6. Step 6: ops API, Bruno collection, frontend harness; then, added during the work: a Playwright folder showing the frontend's API calls and error states, API Gateway protection and throttling, and where the Bruno folder lives.
7. Step 7: design doc, diagrams, backend README, AI-usage notes, PDF, Locust runs for three scenarios.
8. Added along the way: save progress to a resume file before every `/clear`; save the conversation as a deliverable; document the errors we hit; add run instructions and `.env.example` variables; a cache flag with on/off comparison and a rule that a cache costing more than it saves is not used (DAX and the API Gateway cache were rejected on that rule); a requirements-vs-solution-vs-evidence check; a status-latency target (100 ms) that must be met rather than loosened.

## Key decisions
Side-index registry instead of loading engagements; one registry per region (EU, Canada, plus a default region); Java 21 with virtual threads and a permit pool; SQS + DynamoDB + Fargate; worker-owned retry count; decline pins change-units; deterministic summaries with an optional display-only LLM narrative; budget modelled as three caps ($1k, $3k, $10k); plain Java rather than Spring; in-process cache for the offer read only.

## What was built
`backend/` (worker, adapters, ops API, Dockerfile), `infra/` (Terraform module for queues, tables and API Gateway; LocalStack compose), `bruno/`, `frontend/` (+ `playwright/`), `locust/`, `docs/` (plan, design doc, diagrams, notes), `results/` (evidence per layer).

## Corrections made on the way (details in `issues-log.md`)
AIs' claims about reading versions without loading; a wrong 67-day figure; retry counted twice, then SQS `ReceiveCount` misused; ECS on LocalStack; broken Bruno files; slow publish (one SQS call per job); a status endpoint doing one DynamoDB read per job; an API stalled by virtual-thread carrier starvation at 200 permits; a load report that capped drain at the run length (fixed by recording completion during the run).

## How the work ended
- Load results (6 runs: 3 scenarios x cache off/on) all passed correctness. The cache cut offer reads 93-98% but showed no reliable speed gain and saves cents, so the decision, taken from the numbers, is to ship with it off and not to buy DAX or an API Gateway cache. Evidence: `results/locust/ANALYSIS.md`.
- Open limits we chose not to chase (scope was done): EU and Canada workers not run end to end, nothing verified on real AWS, cache A/B is single-run, prices unverified.
- Commits (no co-author line, as asked): `b882abd`, `635c832`, `1ed8a95` and this summary's commit, on `read-llm-history-docs`. A push to GitHub needs the author's credentials (none on this machine).

## State at the end
See `RESUME.md` for the exact state and open items.

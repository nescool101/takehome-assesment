# RESUME (update before every /clear)
Branch `read-llm-history-docs`, worktree `basketstar`. All planned steps (1-7) are done. Last commit message style: no Co-Authored-By line (user's choice).

## State
- Backend: worker, SQS/DynamoDB adapters, ops API, optional offer cache (`CACHE_ENABLED`, off by default). 20/20 tests (`results/unit`, `results/integration`).
- Infra: Terraform (queues, tables, API Gateway module) applied on LocalStack; compose runs localstack + backend + frontend.
- Validations: Bruno 14/14, Playwright 3/3 (system Chrome), Locust 6 runs (3 scenarios x cache off/on) all pass correctness. See `results/locust/REPORT.md` and `ANALYSIS.md`.
- Docs: `docs/design-doc.md/.pdf` (4 pages, follows the brief's 5 sections), `docs/requirements-checklist.md`, `docs/ai-usage-notes.md`, `docs/conversation-summary.md`, `docs/issues-log.md`, diagrams in `docs/diagrams/out/` (5).

## Open items
- User may review the PDF and ask for changes.
- Cache on/off result is inconclusive (single runs, run order confounded); a fair A/B needs off,on,on,off repeated 3x.
- Status-read p95 met 100 ms in 3 of 6 local runs; real DynamoDB not measured.
- API Gateway key/throttle not enforced by LocalStack; verify on AWS.
- Prices in the cost table are from memory, unverified.
- Hook `protect-env` blocks any command containing ".env"; use the Edit/Write tools for `.env.example`.

## How to run
See README "Run it".

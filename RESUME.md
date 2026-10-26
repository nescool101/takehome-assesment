# RESUME (update before every /clear)
Branch `review-fixes` (pushed to origin, PR not opened yet; `main` has the merged first version). All planned steps (1-7) are done, plus the code-review fixes. Commit trailer: `Claude-Session: <url>` (no Co-Authored-By).

## State
- Backend: worker, SQS/DynamoDB adapters, ops API, optional offer cache (`CACHE_ENABLED`, off by default). 26/26 tests, 6 on LocalStack (`results/unit`, `results/integration`). Decisions and offers persist in DynamoDB; published jobs are still in memory.
- Infra: Terraform (queues, tables, API Gateway module) applied on LocalStack; compose runs localstack + backend + frontend.
- Validations (re-run 2026-10-26): Bruno 12 requests / 18 assertions, Playwright 3/3 (system Chrome, run with `PW_PORT=5174`), Locust 6 runs (3 scenarios x cache off/on) all pass correctness. See `results/locust/REPORT.md` and `ANALYSIS.md`.
- Docs: `docs/design-doc.md/.pdf` (4 pages, follows the brief's 5 sections), `docs/requirements-checklist.md`, `docs/ai-usage-notes.md`, `docs/conversation-summary.md`, `docs/issues-log.md`, diagrams in `docs/diagrams/out/` (5).

## Open items
- User may review the PDF and ask for changes.
- Cache on/off result is inconclusive (single runs, run order confounded); a fair A/B needs off,on,on,off repeated 3x.
- Status-read p95 met 100 ms in 2 of 6 local runs; real DynamoDB not measured.
- Docker: `infra-frontend-1` (nginx, :5173) is wedged and OrbStack cannot kill it; needs an OrbStack restart (also stops unrelated containers). Playwright works around it with `PW_PORT`.
- `registry` and `checkpoints` tables are design-only (nothing reads them); published jobs (engagement to offers) are lost on restart.
- Push: HTTPS remote has no credentials; push over SSH (`git push git@github.com:nescool101/takehome-assesment.git <branch>`) or `gh auth login`.
- ecc plugin is enabled at project scope (`.claude/settings.json`, minimal hooks); restart Claude Code to load it, then run its review commands.
- API Gateway key/throttle not enforced by LocalStack; verify on AWS.
- Prices in the cost table are from memory, unverified.
- Hook `protect-env` blocks any command containing ".env"; use the Edit/Write tools for `.env.example`.

## How to run
See README "Run it".

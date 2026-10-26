# RESUME (update before every /clear)
Branch `fix-review-findings` (8 local commits, NOT pushed: no GitHub credentials; run `! gh auth login`). Earlier `review-fixes` was pushed; `main` has the merged first version. All planned steps (1-7) are done, plus the code-review fixes. Commit trailer: `Claude-Session: <url>` (no Co-Authored-By).

## State
- Backend: worker, SQS/DynamoDB adapters, ops API, optional offer cache (`CACHE_ENABLED`, off by default). 30/30 tests, 8 on LocalStack (`mvn verify`; `results/` not re-run yet). Published jobs (`registry`), offers, decisions, per-offer done counter and the shared circuit breaker (ShedLock probe, `<region>-locks`) all persist in DynamoDB. JSON logs + EMF metrics, SIGTERM drain, fail-closed admin/CORS, no hardcoded AWS config. AWS SDK 2.57.3, Jackson 2.22.4, JaCoCo, enforcer, Spotless.
- Infra: Terraform (queues, tables, API Gateway module) applied on LocalStack; compose runs localstack + backend + frontend.
- Validations (re-run 2026-10-26): Bruno 12 requests / 18 assertions, Playwright 3/3 (system Chrome, run with `PW_PORT=5174`), Locust 6 runs (3 scenarios x cache off/on) all pass correctness. See `results/locust/REPORT.md` and `ANALYSIS.md`.
- Docs: `docs/design-doc.md/.pdf` (4 pages, follows the brief's 5 sections), `docs/requirements-checklist.md`, `docs/ai-usage-notes.md`, `docs/conversation-summary.md`, `docs/issues-log.md`, diagrams in `docs/diagrams/out/` (5).

## Open items
- User may review the PDF and ask for changes.
- Cache on/off result is inconclusive (single runs, run order confounded); a fair A/B needs off,on,on,off repeated 3x.
- Status-read p95 met 100 ms in 2 of 6 local runs; real DynamoDB not measured.
- Docker: `infra-frontend-1` (nginx, :5173) is wedged and OrbStack cannot kill it; needs an OrbStack restart (also stops unrelated containers). Playwright works around it with `PW_PORT`.
- `checkpoints` table is design-only. Per-firm limiter is per process. No CloudWatch alarms/dashboards on the EMF metrics. Before real AWS: set `ADMIN_TOKEN`, `terraform apply` (registry schema + locks table). Details in `docs/issues-log.md` rows 29-30.
- Re-run Bruno/Playwright/Locust/Terraform evidence in `results/` after the latest changes (Terraform needs `apply` first).
- Push: HTTPS remote has no credentials; push over SSH (`git push git@github.com:nescool101/takehome-assesment.git <branch>`) or `gh auth login`.
- ecc plugin is enabled at project scope (`.claude/settings.json`, minimal hooks); restart Claude Code to load it, then run its review commands.
- API Gateway key/throttle not enforced by LocalStack; verify on AWS.
- Prices in the cost table are from memory, unverified.
- Hook `protect-env` blocks any command containing ".env"; use the Edit/Write tools for `.env.example`.

## How to run
See README "Run it".

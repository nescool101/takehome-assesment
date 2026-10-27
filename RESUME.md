# RESUME (update before every /clear)
Branch `presentation` (observability + polish for the 2026-10-27 presentation, on top of `fix-review-findings`; pushed over SSH if needed: `git push git@github.com:nescool101/takehome-assesment.git presentation`). All planned steps (1-7) plus review fixes plus the observability round are done. Commit trailer: `Claude-Session: <url>` (no Co-Authored-By).

## State
- Backend: worker, SQS/DynamoDB adapters, ops API, optional offer cache (`CACHE_ENABLED`, off by default). 30/30 tests, 8 on LocalStack (`mvn verify`). Published jobs (`registry`), offers, decisions, per-offer done counter and the shared circuit breaker (ShedLock probe, `<region>-locks`) all persist in DynamoDB. JSON logs + EMF metrics (cumulative counters, flush deltas, `METRICS_FLUSH_SECONDS`), SIGTERM drain, fail-closed admin/CORS, no hardcoded AWS config. AWS SDK 2.57.3, Jackson 2.22.4, JaCoCo, enforcer, Spotless.
- Observability (this round): OTel javaagent in the image (traces → Jaeger, no collector), `GET /prom` Prometheus text endpoint → Prometheus → provisioned Grafana dashboard (`infra/grafana/`), standalone `frontend/dashboard.html`, Terraform `modules/alarms` (DLQ/backlog/dead-lettered + CloudWatch dashboard, `enable_dashboard=false` on LocalStack). Evidence + findings: `results/observability/REPORT.md`.
- Fixed this round: flushMetrics used sumThenReset and zeroed the counters the scrape reads; `dlq.visible` was a JSON string since the Jackson refactor (Bruno re-run caught it — now numbers).
- Infra: Terraform (queues, tables, API Gateway, alarms) applied on LocalStack (`cloudwatch` endpoint override + `SERVICES=...,cloudwatch` added); compose runs localstack + backend + frontend + jaeger + prometheus + grafana.
- Validations re-run 2026-10-27 on this branch: unit/integration 30/30, Bruno 12/18 assertions -> 18/18, Playwright 3/3, Locust 6 runs all pass (peak within permits, 0 duplicates, empty DLQ). Fresh files in `results/` (dated 2026-10-27).
- Docs: `docs/design-doc.md/.pdf` (4 pages, follows the brief's 5 sections), `docs/requirements-checklist.md`, `docs/ai-usage-notes.md`, `docs/conversation-summary.md`, `docs/issues-log.md`, diagrams in `docs/diagrams/out/` (5).

## Open items
- Presentation 2026-10-27: demo knobs are `SIM_LATENCY_MS=3000` (see graphs climb) and the Grafana kiosk view (`http://localhost:3000/d/fanout-load?kiosk`). Screenshots in `results/observability/`.
- `checkpoints` table is design-only. Per-firm limiter is per process. Before real AWS: set `ADMIN_TOKEN`, `terraform apply` (registry schema + locks + alarms with `enable_dashboard=true`), attach SNS topic to `alarm_actions`. Details in `docs/issues-log.md` rows 29-30.
- Integration evidence `results/integration/` still from 2026-10-26 (code untouched this round except metrics bookkeeping; re-run when convenient with `mvn verify`).
- Push: HTTPS remote has no credentials; push over SSH (`git push git@github.com:nescool101/takehome-assesment.git <branch>`) or `gh auth login`.
- ecc plugin is enabled at project scope (`.claude/settings.json`, minimal hooks); restart Claude Code to load it, then run its review commands.
- API Gateway key/throttle not enforced by LocalStack; verify on AWS.
- DynamoDB prices verified 2026-10-27 against aws.amazon.com ($0.625/M writes, $0.125/M reads on-demand — the doc had assumed 2x); capacity-mode decision: on-demand (provisioned breaks even only above 29% sustained utilization, peak-provisioning would cost ~$285/mo vs $4-11). SQS/API Gateway/Fargate prices check out.
- Hook `protect-env` blocks any command containing ".env"; use the Edit/Write tools for `.env.example`.

## How to run
See README "Run it".

# takehome-assesment (Caseware)

Template-update indicator + publish fan-out worker. Full plan: [docs/PLAN.md](docs/PLAN.md).

Layout: `backend/` (Java 21), `frontend/` (static test harness), `infra/` (Terraform + LocalStack), `bruno/` (API tests), `docs/` (design doc, diagrams, AI notes).

## Documentation (start here)
| Read | What it is |
|---|---|
| [docs/design-doc.pdf](docs/design-doc.pdf) / [.md](docs/design-doc.md) | Part 1: the design (4 pages, follows the brief's five sections) |
| [backend/README.md](backend/README.md) | Part 2: the fan-out worker and its implementation tradeoffs |
| [docs/requirements-checklist.md](docs/requirements-checklist.md) | Every requirement in the brief vs our solution vs where the evidence is |
| [docs/diagrams/out/](docs/diagrams/out/) | Architecture, publish sequence, data model, version DAG, status states (sources in `docs/diagrams/*.mmd`) |
| [results/](results/) | Evidence per layer: unit, integration, terraform, bruno, playwright, locust ([REPORT](results/locust/REPORT.md), [ANALYSIS](results/locust/ANALYSIS.md)) |
| [docs/PLAN.md](docs/PLAN.md) | Plan and the decisions taken from the four AI sessions, including contradictions found against the brief |
| [docs/ai-usage-notes.md](docs/ai-usage-notes.md) | Where AI helped, where it was wrong, guidance for engineers, where not to trust it |
| [docs/conversation-summary.md](docs/conversation-summary.md) and [docs/session-history/](docs/session-history/) | Summary of this working session, and the chat text itself |
| [docs/issues-log.md](docs/issues-log.md) | Errors we hit and how we resolved them |
| [RESUME.md](RESUME.md) | State for picking the work up again |

## Architecture decisions

Reached by cross-checking four AI sessions (Kimi, Gemini, Claude, Z.ai; histories in `LLMHistory/`) against the brief and our own reasoning. Where they agreed, we locked it; where they differed, we chose and say why.

### Agreed by all four AIs (and us)
| # | Decision | Why |
|---|---|---|
| D1 | Side-index (registry) of engagements; never scan by loading | 1 min x 40k files = 28 days at the largest firm |
| D2 | UI reads the projection directly; engagement system only at apply/decline | Keeps "seconds" achievable |
| D4 | Summaries generated once per (product, from→to) version pair, cached globally, immutable + provenance | Template content is not firm-specific; inference cost is noise |
| D5 | Global template catalog; per-region (US/EU/CA) registry | Data residency |
| D6 | Java 21, virtual threads + Semaphore; no Kotlin, no reactive | Bottleneck is downstream capacity, not threads |
| D7 | ECS Fargate + SQS + DynamoDB over Lambda | 15-min Lambda cap, one global permit pool, checkpointing |
| D8 | Terraform + LocalStack (SQS, DynamoDB) + Testcontainers | Same IaC local and remote |
| - | Challenged requirement: "real time, within seconds" | Indicator in seconds; summary in minutes (p95 ≤ 15 min) |
| - | Riskiest part: backfill / index drift | Every existing engagement needs one ~1 min load (brief's hard constraint); the registry is only as good as its hooks and backfill |

### Where the AIs disagreed (our call)
| Topic | AI positions | Decision |
|---|---|---|
| What "declined" pins | Kimi: change-units; Z.ai/Gemini: target version | **Change-unit hashes.** Later offers show net-new changes and disclose previously declined ones (handles declined v5 → offered v7) |
| Framework | Gemini: Spring Boot; others: plain | **Plain Java 21 + Maven**, ports/adapters, JDK `HttpServer` |
| Index location | Z.ai: per-tenant DB; Kimi/Claude: registry | **Per-region DynamoDB registry** fed by create/apply/decline hooks |
| LocalStack scope | Gemini: ECS/SNS | **SQS + DynamoDB only** (ECS not in the free tier) |
| Firm count | Kimi assumed 500 | **4,000 firms / 800k engagements** (from the brief) |
| Cost figures | Lambda $1,750 (Claude) vs ~$20 (Z.ai) vs $205 total (Kimi) | **Recomputed ourselves** in the design doc; steady state makes ~0 one-minute calls |
| Extra tests | all flagged over scope | Unit + one LocalStack IT + load sim (Little's Law); Bruno on ops API; frontend is a labeled test harness; full E2E documented, not built |

### Scenarios (configurable, not hard-coded)
Three scale scenarios (OPTIMISTIC / MID / PESSIMISTIC) bundle size, downstream permits and backfill feasibility. Three budget scenarios cap the monthly spend (the brief leaves `$X` blank): $1k / $3k / $10k. All numbers are Java enums with env-var overrides; see `.env.example`.

| | OPTIMISTIC | MID | PESSIMISTIC |
|---|---|---|---|
| Firms / engagements | 4k / 800k | 6k / 1.2M | 12k / 2.4M |
| Downstream permits | 200 | 100 | 20-50 |
| Can list engagement IDs without loading | yes | partial | no |
| Backfill (1 min load each, ÷ permits) | 2.8 days | 8.3 days | 33-83 days |

OPTIMISTIC uses the brief's numbers; the rest are assumptions. Backfill always needs the load; listing IDs only saves discovering them.

### Caveats
- Decisions were validated against the assignment PDF text. Contradictions found (AIs claiming the version is readable without loading, a wrong 67-day figure, double retry, and SQS ReceiveCount inflating on deferrals) are logged and fixed in `docs/PLAN.md`.
- AI output was a draft, not authority: cost arithmetic and the decline semantics were re-derived, not copied.

## Run it (for a person or an AI picking this up)
Needs: Docker, Java 21+, Maven, Terraform, Node, Python 3.11, Chrome. No `.env` file is required: every value has a default in code. To override, copy `.env.example` to `.env` and edit it (`set -a; . ./.env; set +a` for local runs; compose reads it too).

```sh
# 1. infrastructure (LocalStack: SQS, DynamoDB, API Gateway)
docker compose -f infra/docker-compose.yml up -d localstack
(cd infra/terraform/envs/local && terraform init && terraform apply -auto-approve)

# 2. backend + frontend harness + observability (jaeger, prometheus, grafana)
docker compose -f infra/docker-compose.yml up -d --build backend frontend    # API :8080, page http://localhost:5173
# Live load views: Grafana http://localhost:3000/d/fanout-load (anonymous) · http://localhost:5173/dashboard.html · traces http://localhost:16686

# 3. validations (each writes to results/<layer>/)
(cd backend && mvn test)                                                     # unit + LocalStack integration
(cd bruno && bru run --env local)                                            # API contract (npm i -g @usebruno/cli)
(cd frontend/playwright && npm i && npx playwright test)                     # UI + recorded API calls
(cd locust && python3.11 -m venv .venv && .venv/bin/pip install locust && ./run.sh && .venv/bin/python summarize.py)   # 3 scenarios
(cd docs/diagrams && npm i && node render.mjs && node png.mjs)               # diagrams
```
Change scale or budget with `SCENARIO` and `BUDGET` (OPTIMISTIC | MID | PESSIMISTIC), for example `SCENARIO=PESSIMISTIC docker compose -f infra/docker-compose.yml up -d --force-recreate backend`. The first request after a container restart can be slow; wait for `/health`. What went wrong along the way and why: [docs/issues-log.md](docs/issues-log.md). Design: [docs/design-doc.md](docs/design-doc.md). Backend tradeoffs: [backend/README.md](backend/README.md).

## Validation results
Evidence for every layer lives in [`results/`](results/): `unit/` (JUnit reports), `integration/`, `terraform/`, `playwright/` (screenshots), `locust/` (per-scenario reports). Diagrams are Mermaid sources in `docs/diagrams/`, rendered with beautiful-mermaid.

Current (all re-run 2026-10-27 on `presentation`, except the design-doc numbers which are from 2026-10-26):
- **Unit + integration: 30/30** (8 of them on LocalStack). Unit covers config, worker core (permit cap, firm fairness, idempotency, retry/DLQ, withdrawn, breaker, backoff) and the architecture rule ([unit](results/unit/all-tests.txt)). Integration runs the SQS/DynamoDB adapters on LocalStack: duplicates run once, withdrawn skipped, retry then success, fatal to DLQ, expired lease retaken, a foreign worker cannot release a claim, decisions persist, republishing never un-withdraws an offer ([integration](results/integration/)).
- **Terraform on LocalStack:** queue + DLQ + 6 DynamoDB tables per region (the recorded apply shows 5; `locks` was added later), plus the API gateway ([apply](results/terraform/)).
- **Ops API (Bruno):** 12 requests, 18/18 assertions ([run](results/bruno/run.json)). Collection is in [`bruno/`](bruno/). (The re-run surfaced that `dlq.visible` had silently become a JSON string in the Jackson refactor; fixed.)
- **Load (Locust, 3 scenarios x cache off/on, 6 runs, 2026-10-27):** all pass: peak concurrency within permits, 0 duplicate downstream calls, empty DLQ. Measured drain 2.5x to 6.3x the Little's Law prediction; status-read p95 within 100 ms in 2 of 6 runs on LocalStack. The in-process cache cut offer reads 93% to 98% but showed no reliable speed gain, so it ships **off** by default. Report: [REPORT.md](results/locust/REPORT.md); interpretation, limits and the price/quality recommendation: [ANALYSIS.md](results/locust/ANALYSIS.md).
- **Frontend harness (Playwright):** 3/3. Screenshots and the recorded API calls (method, URL, status) are in [`results/playwright/`](results/playwright/): badge and summary after publish, then the error states (backend down, API 500). Specs in [`frontend/playwright/`](frontend/playwright/).
- **Load (Locust, 3 scenarios x cache off/on, 6 runs):** all pass: peak concurrency within permits, 0 duplicate downstream calls, empty DLQ. Measured drain 2.5x to 6.3x the Little's Law prediction; status-read p95 within 100 ms in 2 of 6 runs on LocalStack. The in-process cache cut offer reads 93% to 98% but showed no reliable speed gain, so it ships **off** by default. Report: [REPORT.md](results/locust/REPORT.md); interpretation, limits and the price/quality recommendation: [ANALYSIS.md](results/locust/ANALYSIS.md).
- **Observability:** Grafana/Prometheus live dashboard, Jaeger traces (OTel javaagent), live panel, Terraform alarms — screenshots and findings in [results/observability/REPORT.md](results/observability/REPORT.md).
- **Docs:** [design doc](docs/design-doc.md) ([PDF](docs/design-doc.pdf)), [requirements vs solution vs evidence](docs/requirements-checklist.md), [AI usage notes](docs/ai-usage-notes.md), [conversation summary](docs/conversation-summary.md), [session history (text of the chat)](docs/session-history/), [issues log](docs/issues-log.md), diagrams in [`docs/diagrams/out/`](docs/diagrams/out/).

## Observability (live load)
Three views, all running in compose, evidence and screenshots in [results/observability](results/observability/REPORT.md):
- **Grafana** (http://localhost:3000/d/fanout-load, anonymous): queue depth/DLQ, worker in-flight vs permits, drain rate, downstream concurrency, JVM heap/threads. Prometheus scrapes the backend's `GET /prom` every 5 s.
- **Traces**: the OpenTelemetry javaagent (attached in the Docker image, zero code change) exports the AWS SDK (SQS/DynamoDB) and downstream HTTP spans to Jaeger (http://localhost:16686).
- **Live panel** (http://localhost:5173/dashboard.html): dependency-free page polling `/metrics` at 1 s.
For real AWS the same worker metrics go out as CloudWatch EMF log lines, and `infra/terraform/modules/alarms` adds per-region alarms (DLQ not empty, backlog age, worker dead-lettered) plus a CloudWatch dashboard.

## API Gateway (protects the ops API)
In front of the ops API sits API Gateway ([`infra/terraform/modules/api_gateway`](infra/terraform/modules/api_gateway/main.tf)):
- Every call needs an API key (`api_key_required`), so unauthenticated traffic never reaches the service.
- A usage plan throttles each key (default 50 req/s, burst 100) and caps it at 100,000 requests/day, so one noisy client cannot flood the backend or the downstream permits.
- **Not validated:** LocalStack community accepts the config and proxies requests, but does not enforce keys or throttling (a no-key call returned 200 locally). Enforcement has to be checked on real AWS. The next hardening step is a WAF rate rule plus a proper authorizer for the admin routes.

## Regions and load balancing
Routing is by data residency (a firm's engagements live in its own region's queue and registry). It is not load balancing, so no LB is needed to validate it. Each region is its own stack from the same Terraform module. The brief names two in-region requirements (EU and Canada); we add a default US region for everyone else, so three stacks. The module takes any list.

## Working log
The conversation behind these decisions is kept as a deliverable: decisions in this README and `docs/PLAN.md`, the AI-usage record in `ai-usage-notes.md` (written in step 7). Progress for resuming after a `/clear` is in `RESUME.md`.

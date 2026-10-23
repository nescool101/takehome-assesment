# Caseware take-home — team plan (no code yet)

First action after approval: copy this file to `docs/PLAN.md` in repo.

## 1. Cross-check: other AIs vs our thinking

Sources read: Kimi, Gemini, Claude, Z.ai histories + PROJECT_CONTEXT.md. PDF could not be rendered (no `pdftoppm`); PROJECT_CONTEXT.md §1-5 is its verified summary, used instead.

**Agreed by all 4 (= same as ours, lock):**
- Never scan by loading engagements (1 min x 40k = 28 days). Build a cheap side-index (registry): engagementId, firmId, region, templateId, pinned version, declined state. Publish fan-out = index write/query, not a load.
- Summaries generated ONCE per (product, from→to) version pair, cached globally, immutable + provenance. Inference cost ≈ noise.
- Java 21, virtual threads + Semaphore. No reactive, no Kotlin.
- ECS Fargate + SQS (+ DynamoDB) over Lambda (15-min cap, global permit cap, checkpointing). Lambda only documented as rejected alt / thin triggers.
- Terraform + LocalStack; Testcontainers for ITs.
- Region split: global template catalog; per-region (US/EU/CA) registry.
- Challenge "real time, within seconds": indicator = seconds, summary = minutes (p95 ≤ 15 min).
- Riskiest: backfill / index drift (can firm DBs enumerate engagements without 1-min load?) + decline semantics.

**Disagreements → our call:**
| Topic | AIs said | Decision |
|---|---|---|
| Decline pins what | Kimi: change-units; Z.ai/Gemini: target version | Pin **change-units** (hash set); future offers show net-new only + disclose "includes previously declined v5 changes". Covers v5-declined→v7. |
| Framework | Gemini: Spring Boot; rest: plain | **Plain Java 21 + Maven**, ports/adapters, JDK `HttpServer` for ops API. |
| Index location | Kimi/Claude: registry store; Z.ai: per-tenant DB | **Per-region registry (DynamoDB)** fed by create/apply/decline hooks; keeps residency, avoids touching 4,000 tenant DBs. |
| Gemini: ECS/SNS in LocalStack | wrong-ish (ECS is paid LocalStack) | LocalStack = **SQS + DynamoDB (+S3 if needed)** only. |
| Gemini: summarize-at-publish snapshot only | ok | plus decision record stores (version pair + summary hash) at apply/decline time. |
| Firm count | Kimi: 500 (wrong), brief: 4,000 | Use **4,000 / 800k** (brief). Kimi's O1 was a miss. |
| Cost numbers | Claude 3.5M Lambda calls; Z.ai 692K; Kimi $205 | Don't copy any. **Recompute ourselves**, show arithmetic. Steady state has ~0 one-minute calls. |
| Tests | all over-scope vs brief | T0 must (unit + 1 LocalStack IT + load sim w/ Little's Law), T1 Bruno on ops API, frontend = tiny static test harness, labeled as such. |
| Docs hand-off | Z.ai put "go" pending | Same: this plan = the go. |

## 2. Repo layout (step: create folders)
```
backend/   Java 21 Maven: core (framework-free) + adapters + tests
frontend/  single static page (pending badge + summary) — test harness only
infra/     terraform/{modules/queue_registry, envs/local, envs/aws}, docker-compose.yml (LocalStack)
docs/      PLAN.md, design-doc.md, diagrams/*.mmd, ai-usage-notes.md
bruno/     collection on ops API
locust/    load tests per scenario (optimistic/mid/pessimistic) simulating consumption
.env.example  (committed)   .env (gitignored)
README.md
```
Env notes: local has Java 25, no Maven/Terraform/OpenTofu → target `--release 21`; use Maven wrapper; `brew install terraform` (or opentofu) needed — ask user to run. Docker + node present.

## 3. Terraform / LocalStack
- Module `queue_registry`: SQS work queue + DLQ (redrive, maxReceiveCount=3), DynamoDB `registry` (pk engagementId, GSI firmId+templateId), `offers` (pk offerId = hash(product,targetVersion)), `checkpoints/leases`, `decisions` (audit). One module, regional instances (us/eu/ca).
- `envs/local`: provider endpoints → `http://localhost:4566`, dummy creds (skip_* flags). `envs/aws`: real. Same module, different tfvars.
- `docker-compose.yml`: LocalStack with SERVICES=sqs,dynamodb. Risk: newer LocalStack images may require auth token → pin a community-compatible tag; verify at build time.
- Containers for app: Dockerfile for backend; compose runs localstack + backend + frontend. (Containers via compose; Terraform provisions AWS resources inside LocalStack — no ECS in LocalStack.)

## 4. Env vars / secrets
- Non-secret scale/cost numbers → `.env` (from `.env.example`), read at startup.
- Real secrets (e.g. future LLM API key) → Agent Vault: `<agent-vault:llm-api-key>` placeholder, `agent-vault write`; run via `av-run vault.tpl -- <cmd>`. LocalStack creds are dummy (`test/test`) → plain.
- `.env` in `.gitignore`. User runs any `agent-vault set` themselves.

## 5. Config = enums + env overrides (numbers change easily)
```
Scenario { OPTIMISTIC, MID, PESSIMISTIC }   // env SCENARIO=MID
ScaleProfile(firms, activeEngagements, products, medianPerFirm, maxPerFirm,
             engagementLoadTime, publishCadence, permits, enumerationApiExists, pctBehind)
BudgetScenario { OPTIMISTIC, MID, PESSIMISTIC }  // env BUDGET=MID → monthlyCapUsd
BudgetTier { LEAN, TARGET, CAP }   // from PROJECT_CONTEXT: rule-based / +cached LLM / kill-switch
```
- Each enum holds defaults; any field overridable by env (`FIRMS`, `ENGAGEMENTS`, `PERMITS`, `LOAD_SECONDS`, `BUDGET_CAP_USD`, …). Single `AppConfig.fromEnv()`; tests/docs/cost table all read the same object.
- Scale scenarios (bundle: size + downstream capacity + backfill feasibility):
  | | OPTIMISTIC | MID | PESSIMISTIC |
  |---|---|---|---|
  | firms / engagements | 4k / 800k (brief) | 6k / 1.2M | 12k / 2.4M |
  | downstream permits | 200 | 100 | 20–50 |
  | ID enumeration (list engagement IDs per firm, no load) | yes | partial | no |
  | backfill (800k/1.2M/2.4M × 1 min ÷ permits) | 2.8 d | 8.3 d | 33 d @50 permits, 83 d @20 → active-firm-first + confirm-on-touch |
  Backfill ALWAYS needs one ~1 min load per existing engagement (PDF hard constraint: template info is only readable by loading). Enumeration only saves discovering IDs, never the version read.
  Tuned in `.env`; numbers marked ASSUMPTION in doc.
- **Budget $X unknown** → 3 scenarios as monthly cap: PESSIMISTIC $1k / MID $3k / OPTIMISTIC $10k (Claude's set; Z.ai's $2k/10k/50k and Kimi's $2k rejected as too loose). Doc shows each scenario's estimated cost vs cap, and which tier (LEAN/TARGET/CAP) fits. Design target: low hundreds $/mo.

## 6. Design decisions (log for doc)
D1 side-index, no scan-by-load. D2 UI reads projection directly; engagement system only at apply/decline. D3 decline pins change-unit hashes. D4 deterministic canonical summary (typed+hashed rollup), LLM narrative optional/display-only, numbers never from LLM, provenance (versions, diff hash, renderer/model/prompt ver). D5 global catalog + regional registries. D6 Java21 + Semaphore + virtual threads. D7 Fargate+SQS. D8 Terraform+LocalStack+Testcontainers. D9 withdrawal = tombstone offer + recompute pending from non-withdrawn descendant on the branch. D10 at-least-once + idempotency key (engagementId, templateId, targetVersion) + daily reconciler. D11 migration: hooks dark → backfill under permit budget → confirm-on-touch → per-tenant cutover ≥99% coverage; fail-quiet (no false "pending").

## 7. Worker (Part 2) shape — backend/
Core ports: `JobSource` (SQS), `RegistryStore`, `DownstreamClient` (1-min load, simulated), `CheckpointStore`. Classes: `FanOutWorker` (virtual-thread executor + shared `Semaphore`/permit pool, per-firm fairness so 40k firm can't starve others), `RetryPolicy` (backoff+jitter, max 3 → DLQ), circuit breaker on 429/5xx, `Outcome` sealed (Applied/Skipped/Retryable/Fatal). SQS visibility timeout > call time + margin. Stream/shard, never hold 40k in memory. Ops API (JDK HttpServer): `/health`, `POST /admin/publishes`, `GET /admin/offers/{id}/status`, `/metrics`.

**Downstream call = engagement-system load/confirm (~1 min, other team, limited capacity).** Steady state: publish → registry query → cheap pending-flag write, no load. The worker uses the 1-min call for (a) backfill of unconfirmed rows, (b) best-effort materialization in the engagement system. Retry authority is the worker's own attempt counter (message attribute, bumped only by `JobSource.retry` after a real downstream failure); DLQ at `RETRY_MAX_ATTEMPTS`. Deferrals (firm cap, breaker, duplicate in flight) never consume an attempt. SQS `maxReceiveCount` (Terraform, default 20) is only a poison/crash-loop net, because ReceiveCount also rises on every deferral. Visibility timeout must exceed p99 call time (degraded profile can reach 30 min) → heartbeat/extend; idempotency covers redelivery anyway.

## 8. Tests
- T0 unit: concurrency never > permits, idempotent dup skipped, retry→DLQ, withdrawn skipped, fairness.
- T0 IT: Testcontainers/LocalStack publish→queue→worker→registry; poison-message path.
- Load sim: stub latency configurable (ms in tests), per scenario `@ParameterizedTest`; Little's Law wall ≈ jobs×latency/permits; report extrapolated real duration; assert cap + zero dupes.
- **`locust/`** (user request): `locustfile.py` drives the ops API per scenario. `SCENARIO` env picks users/spawn rate/publish burst from the same numbers as the Java `ScaleProfile` (read from the shared env file, one source of truth). Simulates: 40 products × weekly publishes (burst for PESSIMISTIC), firm-size skew (median 40, max 40k), UI polling of the pending indicator, apply/decline traffic. Downstream stub latency time-compressed (60 s → configurable ms); report extrapolates real duration = measured × compression. Outputs per scenario: p50/p95/p99 indicator latency, permits-in-use ≤ cap, queue depth, DLQ count, duplicates = 0. Run headless in CI (`locust --headless --csv`). Needs `pip install locust` (user/venv inside `locust/`, not global).
- T1 Bruno vs ops API. T2 Playwright/full UI E2E: not built, README "left out + how to validate".

## 8b. Validation matrix (user request: every layer, all scenarios) — overrides earlier "skip Playwright"
| Layer | What is validated | Tool | When |
|---|---|---|---|
| Architecture | PDF constraints hold: no scan-by-load, residency (no engagement ids in global store), permits ≤ cap, backfill/cost arithmetic reproducible from config | ArchUnit-style test on package deps + `ConfigArithmeticTest` + checklist in §"Validation vs the PDF" | each step |
| Microservice layer (core/worker) | Concurrency, idempotency, retry→DLQ, fairness, withdrawn skipped | JUnit 5 unit tests, fakes | step 4 |
| Integration layer | Real SQS visibility/redrive + DynamoDB conditional writes; Terraform-provisioned infra | Testcontainers/LocalStack + `terraform apply` smoke | step 5 |
| API contract | ops API endpoints | Bruno | step 6 |
| Full app running (REQUIRED, frontend testing) | compose up (LocalStack + backend + frontend): publish → badge + summary shown in UI; screenshots saved to `results/playwright/` | Playwright | step 6/7 |
| Load / consumption | 3 scenarios, extrapolated real durations | Locust | step 7 |
Every layer is parameterized by `SCENARIO`/`BUDGET`.

## 8c. Evidence: `results/` (user request)
Every validation layer writes its output to `results/<layer>/` at repo root, committed so the team can verify later and linked from README + design doc:
`results/unit/` (surefire txt/xml), `results/integration/`, `results/terraform/` (apply/plan output), `results/playwright/` (screenshots, traces, report), `results/locust/` (CSV + HTML report per scenario). Each run names files with scenario + date. README has a "Validation results" section embedding the screenshots and linking reports.

## 8d. Diagrams: beautiful-mermaid (user request)
Mermaid sources in `docs/diagrams/*.mmd`; small Node script `docs/diagrams/render.mjs` uses `beautiful-mermaid` (github.com/lukilabs/beautiful-mermaid; verify the package/API when installing, never assumed) to export SVG (+PNG for the PDF) into `docs/diagrams/out/`. README and design doc embed the rendered images; the raw Mermaid stays renderable on GitHub.

## 9. Build order (steps 2→end; deliverables last)
1. Copy plan → docs/PLAN.md; create folders; .gitignore, .env.example.
2. Config enums + env loader (backend) — first, everything depends on it.
3. Terraform module + local env + compose; `terraform apply` against LocalStack.
4. Worker core + unit tests.
5. LocalStack IT + load sim (3 scenarios).
6. Ops API + Bruno; static frontend harness.
7. LAST: design-doc.md (2–4 pages, arithmetic for all 3 scale × 3 budget scenarios), mermaid diagrams (arch/regions, publish sequence, status state, version DAG w/ branch+withdraw+declined-v5→v7, ER), backend README (tradeoffs), ai-usage-notes.md, PDF export.

## 10. Verification
`docker compose up localstack` → `terraform apply` (envs/local) → `mvn verify` (unit+IT+load) → run worker, `bruno run` collection → open frontend, see badge. Change `SCENARIO`/`BUDGET` in `.env` and rerun to confirm numbers flow to tests + cost table.

## Validation vs the PDF (done 2026-10-23, `pdftotext`) — contradictions found and fixed
1. AIs (Kimi/Claude "optimistic: metadata readable without rehydration") **contradict the PDF**: version is readable only by loading. Fixed above; backfill always loads.
2. PDF: engagement stores the **creation** version, not the current one. So backfill from load gives creation version only; applied/declined history before hooks is unknown → hooks on apply/decline (allowed by PDF) are the source of truth going forward; assumption: a load also exposes applied-update history, else treat as unknown/fail-quiet.
3. Z.ai's pessimistic "67 d" is wrong: 2.4M × 1 min ÷ 50 = 33 d (÷20 = 83 d). Our table uses recomputed figures.
4. Part 2 says the worker's downstream call is 1 min/engagement; an earlier draft made steady state ~0 calls and left the worker nothing to throttle. Resolved in §7 above.
5. Retry: SQS maxReceiveCount vs in-process retry would multiply attempts. First fix (SQS count as sole authority) was itself wrong: deferrals also raise ReceiveCount and would dead-letter healthy jobs. Final: worker-owned attempt counter, SQS count = high safety net (found while writing step 4).
6. Residency: global side holds only product/version/opaque ids; engagement ids and firm ids stay in the regional registry (PDF: template DB holds no engagement data — consistent).
7. Assumption stated (PDF silent): the engagement-list UI can call our indicator service instead of the engagement system.
8. Scenario firm counts above 4k/800k are our assumptions, not PDF facts; OPTIMISTIC = the PDF numbers.
Checked consistent: 40 products × 1/wk ≈ 173 publishes/mo; avg 200 eng/firm (800k/4k) vs median 40 fits skew; budget is inclusive of inference; 2–4 page doc; Java required for Part 2 only.
Rule going forward: before finishing each step, re-check against the PDF text and this list; log any new contradiction here.

## Open (defaults chosen; veto if wrong)
- Budget caps $1k/$3k/$10k. Maven. Terraform (not OpenTofu). Frontend = static page. Install terraform via brew (you run it).

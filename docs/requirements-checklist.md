# Requirements vs solution vs evidence

Source: the assignment PDF (`LLMHistory/Staff_Java_Developer_-_Take-Home_Test 2 (24).pdf`). Status is what we can show, not what we hope: **Done** (built and checked), **Designed** (in the design doc only, not built), **Limit** (done with a stated limit).

## Part 1: design document (2-4 pages, diagrams excluded)
| Brief asks for | Where | Status | Evidence |
|---|---|---|---|
| Short document, 2-4 pages | `docs/design-doc.md`, `docs/design-doc.pdf` | Done (4 pages) | `docs/design-doc.pdf` |
| 1. Architecture and data ownership | Section 1 | Done | `docs/diagrams/out/architecture.svg`, `publish-sequence.svg`, `er.svg` |
| 2. Correctness and production evolution: branching versions, withdrawals, v5 declined then v7, accumulating updates, what "declined" pins | Section 2 | Done | `docs/diagrams/out/version-dag.svg`, `status-state.svg` |
| 3. Scale, cost, operations with arithmetic against the given numbers | Section 3 (3 scale x 3 budget scenarios) | Done, prices unverified | `docs/design-doc.md` section 3; `backend/src/main/java/com/caseware/fanout/config/` reproduces the backfill arithmetic, tested in `AppConfigTest` |
| 4. Summary generation and evaluation, defensible in November | Section 4 | Designed | `docs/design-doc.md` section 4 |
| 5. Tradeoffs, assumptions, the one requirement we challenge | Section 5 | Done | `docs/design-doc.md` section 5 |
| Migration and backfill | Sections 2, 3 | Done | backfill days in section 3 |
| Unreliable event delivery | Section 2 | Designed; the worker half is built (idempotency, retry, DLQ) | `results/integration/` |
| Observability and SLOs | Section 3 | Done (live dashboards, traces, alarms as code; SLO thresholds still estimates) | `results/observability/REPORT.md`, `infra/terraform/modules/alarms` |
| Data residency (EU, Canada) | Section 1; per-region Terraform stacks | Done | `infra/terraform/modules/queue_registry`, `results/terraform/` |
| Failure recovery | Section 2 | Done for the worker; rest designed | `results/unit/`, `results/integration/` |
| Riskiest part, and what we left out | Section 5 | Done | `docs/design-doc.md` section 5 |
| Pending indicator works for existing engagements, no maintenance window | Migration plan (hooks dark, backfill, confirm-on-touch) | Designed | section 2 |
| Cost under $X/month including inference | Three assumed caps: $1k / $3k / $10k | Done, assumption | section 3 table |

## Part 2: Java fan-out worker (required)
| Brief asks for | Where | Status | Evidence |
|---|---|---|---|
| Template-publish fan-out worker in Java | `backend/src/main/java/com/caseware/fanout/worker/` | Done | `results/unit/` |
| Concurrency | Permit pool + virtual threads; pulls only as many jobs as free permits | Done | `FanOutWorkerTest` (peak concurrency within permits); `results/locust/REPORT.md` (peak concurrent vs permits) |
| Downstream capacity | Global permits, per-firm cap, circuit breaker | Done | unit tests; load report |
| Failure | Retry with backoff and jitter, then DLQ; fatal goes to DLQ at once | Done | unit tests; `results/integration/` (against real SQS/DynamoDB on LocalStack) |
| Idempotency | DynamoDB conditional put with lease; duplicates run once | Done | integration test; load report shows 0 duplicate downstream calls |
| Interfaces and contracts | `worker/Ports.java`; core has no AWS or Spring imports | Done | `ArchitectureTest` |
| Tests or validation logic (a plus) | 30 unit + integration tests, Bruno, Playwright, Locust | Done | `results/unit`, `results/integration`, `results/bruno`, `results/playwright`, `results/locust` |
| Short README with the tradeoffs | `backend/README.md` | Done | `backend/README.md` |

## Deliverables
| Deliverable | Location | Status |
|---|---|---|
| 1. Design document (PDF or Markdown) | `docs/design-doc.pdf`, `docs/design-doc.md` | Done |
| 2. Code for Part 2 with a short README | `backend/`, `backend/README.md` | Done |
| 3. (Optional) AI session history | `LLMHistory/` (earlier assistants), `docs/session-history/` (text of this session's chat: user and assistant messages, tool output omitted), `docs/ai-usage-notes.md`, `docs/conversation-summary.md`, `docs/issues-log.md` | Done; the notes and summary are written by the assistant, the session history is the chat text itself |
| 4. Diagrams | `docs/diagrams/*.mmd` (sources), `docs/diagrams/out/` (SVG + PNG) | Done |

## AI usage questions the reviewers will ask
| Question | Answer lives in |
|---|---|
| Where AI helped | `docs/ai-usage-notes.md` (per deliverable) |
| Where we corrected or ignored AI output | `docs/ai-usage-notes.md` ("Where AI was wrong"), `docs/PLAN.md` ("Validation vs the PDF"), `docs/issues-log.md` |
| How to guide engineers using AI here | `docs/ai-usage-notes.md` ("Guidance for engineers") |
| Where AI should not be trusted in this domain | `docs/ai-usage-notes.md` ("Where AI should not be trusted") |

## Beyond the brief (extras we added)
Load test with cache off vs on across 3 scenarios; Playwright evidence of the frontend's API calls and error states; Bruno API contract run; API Gateway module (API key, throttle, quota); in-process cache with a cost-based decision on DAX and gateway caching; issues log of what went wrong; run instructions in the README; Terraform applied on LocalStack for three regions.

## Evidence folder map
| Folder | Contents |
|---|---|
| `results/unit/` | JUnit reports for config and worker tests |
| `results/integration/` | LocalStack SQS/DynamoDB test report |
| `results/terraform/` | `terraform apply` and verification output |
| `results/bruno/` | API collection run |
| `results/playwright/` | Screenshots, recorded frontend-to-API calls, HTML report, traces |
| `results/locust/` | `REPORT.md`, per-run CSV, HTML, summary JSON (cache off and on) |

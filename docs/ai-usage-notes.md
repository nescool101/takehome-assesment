# AI usage notes

How AI was used on this take-home, what we accepted, what we rejected, and how we checked it. Organized by deliverable. The raw conversations with the four earlier assistants are in `LLMHistory/` at the repo root; this session's working log is summarized below.

## Process
1. **Four earlier assistants (Kimi, Gemini, Claude, Z.ai)** each produced a draft approach. They are inputs, not authority.
2. **This session (Claude Code, driven by the author)** read those histories and the assignment PDF text, compared the drafts, made the decisions in `docs/PLAN.md`, then built and validated step by step. The author directed each step ("go ahead with step N") and added requirements as the work went (validation per layer, evidence in `results/`, Playwright, Locust, API Gateway, run instructions).
3. **Rule applied throughout:** re-check every step against the PDF text and record contradictions. Findings are in `docs/PLAN.md` (section "Validation vs the PDF") and `docs/issues-log.md`.

## Per deliverable
| Deliverable | What AI did | What the author decided or corrected | How it was checked |
|---|---|---|---|
| `docs/PLAN.md`, README decisions | Compared the four drafts, listed agreements and disagreements, proposed calls | Asked for the decisions from all four conversations to be recorded in the README; asked to validate against the PDF and avoid contradictions | PDF text (`pdftotext`); contradictions logged and fixed |
| Config (`config/`) | Wrote enums + env overrides | Scenario and budget structure | 5 unit tests; recomputed backfill figures by hand |
| Terraform + compose | Module `queue_registry`, local and AWS envs, API gateway module | Asked for API Gateway protection and throttling | `terraform apply` on LocalStack, results in `results/terraform/`; gateway enforcement could not be verified locally |
| Worker core (`worker/`) | Wrote the worker, limiter, breaker, backoff | Required validation per architecture layer | 16 unit tests, plus a mutation check (disabling the firm cap, then the idempotency check, made the matching test fail) |
| Adapters + integration | SQS, DynamoDB adapters and LocalStack tests | n/a | 3 integration tests against real LocalStack |
| Ops API, Bruno, frontend, Playwright | Built the API, harness page, collection and specs | Asked for a Playwright folder with evidence of the frontend's API calls and of error handling | Bruno 14/14, Playwright 3/3 with screenshots and recorded calls |
| Load test (`locust/`) | Wrote the locustfile, runner and report scripts | Asked for 3 scenarios and the results saved in the report and README | Measured drain compared with Little's Law; see `results/locust/REPORT.md` |
| `docs/design-doc.md` | Drafted text and cost arithmetic | Budget caps and scenario sizes are assumptions agreed in the plan | Arithmetic re-added by hand; prices flagged as unverified |
| Diagrams | Wrote Mermaid sources, rendered with beautiful-mermaid | Asked for the tool | Looked at the rendered images; fixed one overlapping label |

## Where AI was wrong and we caught it
- Two assistants said an engagement's version is readable without loading it; the PDF says otherwise.
- One assistant's pessimistic backfill figure (67 days) was wrong (33 days).
- Our own first retry design used SQS `ReceiveCount`, which deferrals also raise; found while writing the worker.
- One assistant proposed ECS/SNS on LocalStack; ECS is not in the free LocalStack.
- The first Bruno files and the first load run were broken (format, empty run); both fixed and logged.
- An API hang was misread at first; the thread dump showed the server idle and pointed at thread starvation. This remains our diagnosis rather than a profiled result.

## Guidance for engineers using AI on this system
- **Give the AI the constraints, then check its arithmetic.** The 1-minute load, the permit cap and residency are the facts that decide the design. Two assistants broke the first one; recompute any number an AI states.
- **Make it show evidence.** For every claim ("this retries correctly", "this fits the budget") ask for the test, the measurement or the arithmetic, and run it. In this project a mutation check (disable the guard, see the test fail) caught tests that proved nothing.
- **Treat AI explanations of failures as hypotheses.** Our API hang was first misread; the thread dump and a fix that removed the symptom were what settled it, and the notes still call it a diagnosis, not a profile.
- **Keep decisions in the repo, not the chat.** `docs/PLAN.md` and `docs/issues-log.md` record what was decided and what went wrong, so the next person does not repeat it.
- **Pin and version anything an AI generates that a user will see** (see design doc section 4).

## Where AI should not be trusted in this domain
- **Regulated summaries.** An LLM must not be the source of any number, name or section shown to an auditor; it may only reword text derived mechanically from the diff, and must be reproducible from stored inputs.
- **Capacity and cost numbers.** Prices, downstream capacity and "typical" volumes are guesses; we mark them as assumptions and re-verify before relying on them.
- **Concurrency and delivery semantics.** Plausible-looking retry and idempotency logic can be wrong (our first retry design was); only tests against real SQS/DynamoDB behaviour settle it.
- **Data residency.** An AI will happily put a convenient global cache or index in the wrong place; check every data flow against where engagement and firm ids live.
- **Claims about tools it has not run** (for example that LocalStack enforces API Gateway throttling; it does not).

## Limits of this record
It is a summary written by the assistant from this session's work, not a verbatim transcript. Prices in the design doc are from memory and unverified. Anything marked ASSUMPTION in the design doc is the team's choice, not a fact from the brief.

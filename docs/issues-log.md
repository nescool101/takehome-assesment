# Issues we hit and how we resolved them

Kept so reviewers can see what went wrong, not just the final state. Newest last. "Root cause" is what we verified, not a guess.

## Design / plan contradictions (found by checking AI output against the assignment PDF)
| # | Issue | Fix |
|---|---|---|
| 1 | Two AIs assumed an engagement's template version is readable without loading it. The PDF says it is only readable by loading (about 1 minute). | Backfill always costs one load per existing engagement; enumeration only saves finding IDs. |
| 2 | An AI quoted a 67-day backfill for the pessimistic case. 2.4M x 1 min / 50 permits is 33 days (83 at 20 permits). | Recomputed; the doc shows the arithmetic. |
| 3 | The engagement stores the creation version, not the current one. | Hooks on apply/decline are the source of truth going forward. |
| 4 | Retry counted twice: SQS `maxReceiveCount` plus in-process retry would multiply attempts. | First fix (SQS count only) was also wrong, see next row. |
| 5 | SQS `ReceiveCount` also rises on every deferral (firm cap, breaker open), so using it as the retry counter would dead-letter healthy jobs. | Worker keeps its own attempt count (message attribute). `maxReceiveCount` is a safety net at 20. Terraform re-applied. |
| 6 | An early draft left the worker with nothing to throttle (steady state made no 1-minute calls). | The 1-minute call is used for backfill and best-effort materialization (plan section 7). |

## Tooling and environment
| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 7 | Could not render the PDF. | `pdftoppm` missing. | Installed poppler; validated with `pdftotext`. |
| 8 | `.env` could not be created. | A `protect-env` hook blocks any write to `.env` files, and also any command whose text contains `.env`. | `.env` is optional: every value has a default in code and `.env.example` documents them. Users run `cp .env.example .env` themselves. |
| 9 | Test run hung (mutation check). | The test drain loop had no time limit. | Loop stops after 10 s and the test fails instead. |
| 10 | `docker compose up localstack` pinned to 3.8. | Newer LocalStack tags can require an auth token. | Pinned `localstack/localstack:3.8`. |
| 11 | `terraform apply` failed: "Service 'apigateway' is not enabled". | LocalStack `SERVICES` only listed sqs,dynamodb. | Added `apigateway`. Recreating the container wiped its state, so Terraform was re-applied (30 resources). |
| 12 | API Gateway API key and throttling are not enforced locally: a call with no key returned 200. | LocalStack community does not enforce usage plans. | Documented. Enforcement must be checked on real AWS. |
| 13 | Bruno skipped every request: "Expected ':'". | Single-line `.bru` blocks are invalid; blocks need one key per line. | Regenerated the files in the multi-line format. A leftover old file was also deleted. |
| 14 | Playwright `install chromium` hung and tests failed with "browser not installed". | The bundled-browser download stalled. | Config uses the system Chrome (`channel: "chrome"`). |
| 15 | Scripted `sed` edits failed on macOS (BSD sed). | GNU-style `sed -i` and newlines in patterns. | Used `sed -i ''` or Python edits. |

## Load test
| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 16 | First request after a backend container restart took 5 to 15 s. | Docker's port forward and JVM warm-up on a fresh container. | `locust/run.sh` waits for `/health` and sends a warm-up call before measuring. |
| 17 | Locust saved an empty offers list and `report.py` crashed with `max() arg is an empty sequence`. | Publishing sent each job to SQS one call at a time (800+ calls). The 20 s test ended before the publish finished. | Publish now uses SQS batch sends (10 per call) and the run time is 30 s. |
| 18 | Backend container looked up but every request hung, even from inside the Docker network. The thread dump showed the HTTP dispatcher idle. | The API ran on virtual threads that share carrier threads with the worker; a queue backlog left by earlier killed runs kept the carriers busy (blocking SDK calls pin them). This is our diagnosis from the dump and the fix working, not a profiler result. | Ops API handlers moved to platform threads (the worker keeps virtual threads, per D6). `run.sh` also purges the queues before each scenario. |
| 19 | First locust run reported zero requests and saved offers under "UNKNOWN". | Backend was not answering yet (see 18). | Wait for `/health` with a short timeout; fixed with 18. |
| 20 | Offer status returned 500, then hung, in OPTIMISTIC and MID runs. | The status call did one DynamoDB read per job (800 to 4,000 per poll). | Status now uses BatchGetItem, 100 keys per call (`DynamoIdempotency.countDone`), covered by an integration test. |
| 21 | After 20, OPTIMISTIC (200 permits) still stalled: 143 downstream calls in 4 minutes, even a one-engagement status call timed out, while PESSIMISTIC (50 permits) passed. The thread dump showed API threads blocked inside the SDK call. | Our diagnosis: JDK 21 pins a carrier thread while a virtual thread blocks in the SDK's HTTP client, and the default carrier pool is one per CPU (10 here), so 200 permits starve it. Not proven with a profiler; the fix below removed the symptom. | JVM flags `jdk.virtualThreadScheduler.parallelism=256` and `maxPoolSize=512` in the Dockerfile. Virtual threads kept (D6). After it, 800 jobs finished in about 4 s at 200 permits. |
| 22 | OPTIMISTIC and MID reported a drain of exactly 30 s (25x the Little's Law prediction) in the first complete run. | `report.py` only started polling after Locust's 30 s run ended, so any drain faster than 30 s was reported as 30 s. A measurement artifact, not a slow system. | Completion and badge times are now recorded live by a watcher inside `locustfile.py` (1 s granularity); run time is 45 s. |

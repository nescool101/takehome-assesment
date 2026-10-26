# Backend: fan-out worker + ops API

Plain Java 21 + Maven. No framework. The core (`worker/`) has no AWS or Spring imports (enforced by `ArchitectureTest`); adapters live in `aws/`, the HTTP edge in `api/`.

```
worker/  FanOutWorker, FirmLimiter, CircuitBreaker, Backoff, Ports (interfaces), Job, Outcome
aws/     SqsJobSource, DynamoIdempotency, DynamoOfferCatalog
api/     OpsApi (JDK HttpServer), SimulatedDownstream (the 1-minute call, time-compressed)
config/  Scenario, ScaleProfile, BudgetScenario, BudgetTier, AppConfig (env overrides)
Main     wires one region: queue "<REGION_NAME>-fanout", tables "<REGION_NAME>-*"
```

## Run
```sh
mvn test                                  # 23 tests; the 4 LocalStack tests skip if LocalStack is down
mvn -q dependency:copy-dependencies -DoutputDirectory=target/lib
java -cp 'target/classes:target/lib/*' com.caseware.fanout.Main   # needs LocalStack + terraform applied
```
Full stack and load tests: see the root README ("Run it").

## Tradeoffs
| Choice | Why | Cost / ceiling |
|---|---|---|
| Worker owns the retry count (message attribute); defer and retry both re-send the message | SQS `ReceiveCount` rises on every receive, so a deferred job (firm cap, breaker open, in progress) would hit the DLQ redrive limit unprocessed | Re-send is send-then-delete: a crash between them duplicates the job (idempotency absorbs it). One extra SQS call per deferral. Delays round up to whole seconds |
| Pull only as many messages as free permits | Never hold 40,000 jobs in memory; the permit pool is the backpressure | Throughput is permits / call latency by design |
| Per-firm cap = half the permits (`Main`) | One 40,000-engagement firm cannot starve the rest | Fixed ratio; tune from load results |
| Idempotency = one DynamoDB conditional put with a lease (2x the queue visibility timeout, 10 min by default) | Atomic, no extra service. Claims carry an owner id, so a stalled worker cannot release a claim another worker took. DONE rows expire after 30 days (DynamoDB TTL on `exp`) | A call longer than the lease can run twice; the lease must exceed p99 call time |
| Bookkeeping errors after a successful load (mark done, ack) never release the key | Releasing would repeat a 1-minute downstream call and count a failure that did not happen | The message is redelivered; a stuck key waits out the lease |
| Ops API on platform threads, worker on virtual threads | API handlers shared virtual-thread carriers with the worker and starved behind a backlog (see `docs/issues-log.md`) | Slightly heavier threads for a low-traffic API |
| Publish state and decisions held in memory in `OpsApi` | Keeps the harness small | Lost on restart; the real home is the registry and decisions tables. Apply and decline both only clear the badge for offers the user could see |
| Mutating calls must be `application/json`; optional `ADMIN_TOKEN` header (`x-admin-token`) on `/admin/*` | A cross-site page can only send `text/plain` without a CORS preflight, so it cannot reach the API; the gateway key does not cover :8080 | A shared secret, not real auth. CORS is still `*` for the harness; restrict it with real auth |
| Ids restricted to `[A-Za-z0-9._:-]` | `\|` and `@` are separators in keys and offer ids, quotes break the hand-built JSON | Rejects ids with other characters (400) |
| Offer key is plain `templateId\|version` | Simple to inspect | Terraform comments say "hash"; reconcile them |
| JSON parsed with regex in `OpsApi` (flat objects, any field order) | No dependency for a 4-field body | Nested objects are not supported; use Jackson if the API grows |

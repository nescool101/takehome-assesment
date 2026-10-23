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
mvn test                                  # 19 tests; the 3 LocalStack tests skip if LocalStack is down
mvn -q dependency:copy-dependencies -DoutputDirectory=target/lib
java -cp 'target/classes:target/lib/*' com.caseware.fanout.Main   # needs LocalStack + terraform applied
```
Full stack and load tests: see the root README ("Run it").

## Tradeoffs
| Choice | Why | Cost / ceiling |
|---|---|---|
| Worker owns the retry count (message attribute) | SQS `ReceiveCount` also rises on deferrals, so it would dead-letter healthy jobs | Retry sends a new message then deletes the old one: a crash between them duplicates the job (idempotency absorbs it) |
| Pull only as many messages as free permits | Never hold 40,000 jobs in memory; the permit pool is the backpressure | Throughput is permits / call latency by design |
| Per-firm cap = half the permits (`Main`) | One 40,000-engagement firm cannot starve the rest | Fixed ratio; tune from load results |
| Idempotency = one DynamoDB conditional put with a 10-minute lease | Atomic, no extra service | A call longer than the lease can run twice; the lease must exceed p99 call time |
| Ops API on platform threads, worker on virtual threads | API handlers shared virtual-thread carriers with the worker and starved behind a backlog (see `docs/issues-log.md`) | Slightly heavier threads for a low-traffic API |
| Publish state and decisions held in memory in `OpsApi` | Keeps the harness small | Lost on restart; the real home is the registry and decisions tables |
| Offer key is plain `templateId\|version` | Simple to inspect | Terraform comments say "hash"; reconcile them |
| JSON parsed with regex in `OpsApi` | No dependency for a 4-field body | Fragile for general input; use Jackson if the API grows |

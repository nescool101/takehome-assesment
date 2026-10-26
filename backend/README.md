# Backend: fan-out worker + ops API

Plain Java 21 + Maven. No framework. The core (`worker/`) has no AWS or Spring imports (enforced by `ArchitectureTest`); adapters live in `aws/`, the HTTP edge in `api/`.

```
worker/  FanOutWorker, FirmLimiter, CircuitBreaker, Backoff, Ports (interfaces), Job, Outcome
aws/     SqsJobSource, DynamoIdempotency, DynamoOfferCatalog, DynamoDecisions
api/     OpsApi (JDK HttpServer), SimulatedDownstream (the 1-minute call, time-compressed)
config/  Scenario, ScaleProfile, BudgetScenario, BudgetTier, AppConfig (env overrides)
Main     wires one region: queue "<REGION_NAME>-fanout", tables "<REGION_NAME>-*"
```

## Run
```sh
mvn test                                  # 26 tests; the 6 LocalStack tests skip if LocalStack is down
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
| Published jobs held in memory in `OpsApi`; offers and decisions persisted (`offers` and `decisions` tables) | Keeps the harness small while withdrawals and decisions survive a restart | The job list (engagement to offers) is lost on restart, so badges disappear until republished; the real home is the `registry` table, which nothing uses yet. Apply and decline both only clear the badge for offers the user could see; the action is stored for the audit trail |
| Mutating calls must be `application/json`; optional `ADMIN_TOKEN` header (`x-admin-token`) on `/admin/*` | A cross-site page can only send `text/plain` without a CORS preflight, so it cannot reach the API; the gateway key does not cover :8080 | A shared secret, not real auth. CORS is still `*` for the harness; restrict it with real auth |
| Ids restricted to `[A-Za-z0-9._:-]` | `\|` and `@` are separators in keys and offer ids, quotes break the hand-built JSON | Rejects ids with other characters (400) |
| Offer key is plain `templateId\|version` | Simple to inspect | Terraform comments say "hash"; reconcile them |
| Request bodies parsed with Jackson (`jackson-databind`); responses still built by string concatenation | Correct parsing of any field order, extra fields and malformed input (400) | Responses are safe only because ids are validated; switch to `ObjectMapper` output if free text enters a response |
| Publish registers the offer in the `offers` table; withdraw needs a known offer | A typo'd offer id returns 400 instead of creating a bogus row; republishing never un-withdraws | One extra DynamoDB write per publish |
| Breaker threshold/cooldown and defer delay are env knobs (`BREAKER_FAILURES`, `BREAKER_COOLDOWN_MS`, `DEFER_DELAY_MS`) | Tunable from load results without a rebuild | Per-firm cap is still half the permits (fixed in `Main`) |

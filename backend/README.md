# Backend: fan-out worker + ops API

Plain Java 21 + Maven. No framework. The core (`worker/`) has no AWS or Spring imports (enforced by `ArchitectureTest`); adapters live in `aws/`, the HTTP edge in `api/`.

```
worker/  FanOutWorker, FirmLimiter, CircuitBreaker + BreakerStore, Backoff, Log (JSON lines + EMF), Ports (interfaces), Job, Outcome
aws/     SqsJobSource, DynamoIdempotency, DynamoOfferCatalog, DynamoDecisions, DynamoRegistry, DynamoBreakerStore (ShedLock)
api/     OpsApi (JDK HttpServer)
sim/     SimulatedDownstream (the 1-minute call, time-compressed)
config/  Scenario, ScaleProfile, BudgetScenario, BudgetTier, AppConfig (env overrides)
Main     wires one region: queue "<REGION_NAME>-fanout", tables "<REGION_NAME>-*"
```

## Run
```sh
mvn verify                                 # 30 tests; the 8 LocalStack tests skip if LocalStack is down
mvn -q dependency:copy-dependencies -DoutputDirectory=target/lib
java -cp 'target/classes:target/lib/*' com.caseware.fanout.Main   # needs LocalStack + terraform applied
```
Full stack and load tests: see the root README ("Run it").

## Tradeoffs
| Choice | Why | Cost / ceiling |
|---|---|---|
| Worker owns the retry count (message attribute); defer and retry both re-send the message | SQS `ReceiveCount` rises on every receive, so a deferred job (firm cap, breaker open, in progress) would hit the DLQ redrive limit unprocessed | Re-send is send-then-delete: a crash between them duplicates the job (idempotency absorbs it). One extra SQS call per deferral. Delays round up to whole seconds |
| Pull only as many messages as free permits | Never hold 40,000 jobs in memory; the permit pool is the backpressure | Throughput is permits / call latency by design |
| Per-firm cap = min(half the permits, `permits / active firms`) | One 40,000-engagement firm cannot starve the rest, and several big firms cannot split all permits | Shares apply to new acquisitions only (in-flight calls are never evicted); the limiter is per process |
| Circuit breaker state is shared per region: failure count and reopen time in one DynamoDB item (atomic `ADD` / conditional writes, 500 ms read cache); the half-open probe is a ShedLock lock held for the cooldown | All workers trip and recover together and only one probes. Store down: a worker falls back to its own count | Needs the `<region>-locks` table; counters decay only on a success |
| Poll loop pauses while the breaker is cooling down | No receive+resend+delete per message (a deferral storm) while the downstream is down | Messages wait in the queue instead |
| `done()` is retried 3x in process and also bumps a per-offer counter in the same DynamoDB transaction | A lost `done()` would leave the claim IN_PROGRESS and repeat the 1 min call after the lease; the counter makes offer status one item read | Jobs finished before the counter existed are not counted |
| Idempotency = one DynamoDB conditional put with a lease (2x the queue visibility timeout, 10 min by default) | Atomic, no extra service. Claims carry an owner id, so a stalled worker cannot release a claim another worker took. DONE rows expire after 30 days (DynamoDB TTL on `exp`) | A call longer than the lease can run twice; the lease must exceed p99 call time |
| Bookkeeping errors after a successful load (mark done, ack) never release the key | Releasing would repeat a 1-minute downstream call and count a failure that did not happen | The message is redelivered; a stuck key waits out the lease |
| Ops API on platform threads, worker on virtual threads | API handlers shared virtual-thread carriers with the worker and starved behind a backlog (see `docs/issues-log.md`) | Slightly heavier threads for a low-traffic API |
| Published jobs, offers and decisions all persisted (`registry`, `offers`, `decisions` tables); `OpsApi` holds nothing in memory | Badges and offer status survive a restart. The badge is one registry query + one decisions query + one BatchGet | The registry GSI `offer-index` is eventually consistent (a job published a moment ago may not show in offer status); `/metrics` `published` is a full COUNT scan. Apply and decline both only clear the badge for offers the user could see; the action is stored for the audit trail |
| Mutating calls must be `application/json`; `/admin/*` needs the `x-admin-token` header (`ADMIN_TOKEN`, constant-time compare). With no token, admin is open only when `AWS_ENDPOINT_URL` is set (LocalStack); on real AWS it returns 403 | A cross-site page can only send `text/plain` without a CORS preflight; the gateway key does not cover :8080; fail closed outside local dev | A shared secret, not real auth. CORS is `CORS_ORIGIN`, default `*` on LocalStack and none on real AWS |
| HTTP server uses a fixed 32-thread pool; 500s return `internal error` and log the cause | No unbounded threads, no exception text leaking to callers | A burst beyond 32 concurrent requests queues |
| No endpoint, region or credential defaults in code; `AWS_ENDPOINT_URL` (LocalStack only) is optional and everything else comes from the default AWS chain | Compose, Kubernetes ConfigMap/Secret, IRSA / pod identity or the ECS task role inject them | Local runs need `AWS_REGION` and the dummy keys from `.env.example` / compose |
| Logs are JSON lines on stdout; a per-minute EMF line (namespace `Fanout`, dimension `Service`) carries Done/Skipped/Deferred/Retried/DeadLettered/InFlight | CloudWatch Logs + metrics with no agent code | Alarms and dashboards are not built yet |
| SIGTERM stops polling and waits for in-flight calls | An ECS/Kubernetes deploy does not leave claims waiting for their lease | A 1 min call can exceed the default 30 s stop timeout; raise `stopTimeout` / `terminationGracePeriodSeconds` |
| Ids restricted to `[A-Za-z0-9._:-]` | `\|` and `@` are separators in keys and offer ids, quotes break the hand-built JSON | Rejects ids with other characters (400) |
| Offer key is plain `templateId\|version` | Simple to inspect | Terraform comments say "hash"; reconcile them |
| Request bodies and responses both go through Jackson (`ObjectMapper`) | Correct parsing of any field order, extra fields and malformed input (400); output is always escaped | None |
| Publish registers the offer in the `offers` table; withdraw needs a known offer | A typo'd offer id returns 400 instead of creating a bogus row; republishing never un-withdraws | One extra DynamoDB write per publish |
| Breaker threshold/cooldown and defer delay are env knobs (`BREAKER_FAILURES`, `BREAKER_COOLDOWN_MS`, `DEFER_DELAY_MS`) | Tunable from load results without a rebuild | Hard per-firm cap is still half the permits (fixed in `Main`) |

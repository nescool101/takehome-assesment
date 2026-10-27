# Presentation round — observability & polish (2026-10-27)

Branch `presentation`. Everything here runs in compose and was validated end to end today.

## What we added today

| Piece | What it gives the demo |
|---|---|
| **OpenTelemetry javaagent** (pinned 2.14.0, attached in the Docker image — zero code change) | Traces for every SQS / DynamoDB / downstream-HTTP call, exported OTLP → Jaeger (http://localhost:16686) |
| **`GET /prom`** Prometheus-text endpoint in the ops API | Queue + DLQ depth, worker outcome counters, downstream concurrency, JVM heap/threads — scraped every 5 s |
| **Prometheus + Grafana** (provisioned, anonymous) | 12-panel live load dashboard: http://localhost:3000/d/fanout-load |
| **`frontend/dashboard.html`** | Dependency-free live panel polling `/metrics` at 1 s: http://localhost:5173/dashboard.html |
| **Terraform `modules/alarms`** | Per region: DLQ not empty, backlog age > 600 s, worker dead-lettered (EMF counter) + a CloudWatch dashboard. This closes the "Observability and SLOs" item the checklist had as *designed only* — it is now **done** |

## The screenshots

### Grafana — live load (mid-drain, 300 jobs)
![Grafana live dashboard](../results/observability/grafana.png)
Worker at 79% of permits, backlog draining, downstream peak 87 concurrent, **DLQ 0, duplicate downstream calls 0**.

### Live ops panel (works even without Grafana)
![Live dashboard](../results/observability/live-dashboard.png)
90 of 100 permits busy, queue draining, DLQ green.

### Jaeger — per-message traces
![Jaeger traces](../results/observability/jaeger-traces.png)
`Sqs.DeleteMessage` spans and friends on `fanout-worker` — the waterfall a reviewer would ask for.

## What the metrics caught the same day (the staff-engineer story)

1. **Counter-reset bug**: `flushMetrics` used `sumThenReset`, zeroing the same counters a scraper reads — Prometheus would have seen a reset every 5 s and garbage rates. Fixed: counters are cumulative, the EMF flush computes deltas.
2. **Gauge semantics**: the permit semaphore reads ~10/100 "in-flight" on an *idle* system because the poll loop reserves the next batch's slots during the SQS long-poll. The scrape now reports jobs inside `process()`; the capacity view stays on the EMF line.
3. **Silent contract drift**: the Bruno re-run caught `dlq.visible` serialized as a JSON *string* since the Jackson refactor — the recorded 18/18 predated it. Fixed at the source (`depth()` parses to numbers).

## Demo script (5 minutes)

```sh
docker compose -f infra/docker-compose.yml up -d                      # everything
(cd infra/terraform/envs/local && terraform apply -auto-approve)      # queues/tables/alarms
SIM_LATENCY_MS=3000 docker compose -f infra/docker-compose.yml up -d --force-recreate backend
# publish 300 (button on http://localhost:5173, or the locust run) and narrate:
#   :3000/d/fanout-load?kiosk   — backlog drops, worker gauge climbs, DLQ/duplicates stay 0
#   :16686                      — open one trace, show SQS → DynamoDB → downstream waterfall
#   :5173/dashboard.html        — ops view without any observability stack
```

## What is still open (said out loud, not hidden)

| Gap | Why it's acceptable / next step |
|---|---|
| Summary generation (brief section 4) is design-only | Costed and designed in the design doc; the fan-out core was the build target |
| `checkpoints` table is design-only — backfill has no checkpointing | Worker has none; registry hooks + confirm-on-touch carry correctness |
| Per-firm limiter is per-process | Correct for one worker; shared limiter (DynamoDB tokens) is the multi-worker step |
| Real-AWS validation of alarms/gateway enforcement | LocalStack community does not enforce API keys or `PutDashboard`; alarms module takes `enable_dashboard=true` there; wire SNS to `alarm_actions` |
| SLO thresholds are estimates | Alarm thresholds are first guesses; tune after real-AWS data |
| Cost table prices unverified | Recompute before quoting numbers |
| `results/integration/` reports from 2026-10-26 | Refreshed 2026-10-27 (8/8 LocalStack); `mvn verify` re-runs them any time |

Evidence for every layer: [results/](../results/) — unit 30/30, Bruno 18/18, Playwright 3/3, Locust 6/6 (2026-10-27).

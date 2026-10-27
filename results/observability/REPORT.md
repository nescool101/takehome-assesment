# Observability — live load (presentation round)

Added 2026-10-27 on branch `presentation`. Everything runs locally in compose; no code dependency was added to the backend (the OpenTelemetry agent attaches at runtime).

## What was added

| Piece | What it does | Where |
|---|---|---|
| OTel javaagent | Traces for the AWS SDK (SQS send/receive/delete, DynamoDB) and the downstream HTTP call, plus service name — zero code change | `backend/Dockerfile` (`JAVA_TOOL_OPTIONS=-javaagent:/app/otel.jar`, pinned v2.14.0) |
| Jaeger (all-in-one) | Trace UI; the agent exports OTLP straight to it (no collector needed) | `infra/docker-compose.yml`, UI http://localhost:16686 |
| `/prom` endpoint | Prometheus text format: queue/DLQ depth, published, worker outcome counters, downstream concurrency/duplicates, JVM heap/threads | `OpsApi.prom()`, scraped by Prometheus every 5s |
| Prometheus + Grafana | Provisioned 12-panel live dashboard (no login for the demo) | `infra/prometheus.yml`, `infra/grafana/`, UI http://localhost:3000/d/fanout-load |
| Live panel | Standalone page polling the ops API `/metrics` at 1s — works even without Grafana | `frontend/dashboard.html`, http://localhost:5173/dashboard.html |
| Alarms as code | Per region: DLQ not empty, backlog age > 600s, worker dead-lettered (EMF counter) + a CloudWatch dashboard | `infra/terraform/modules/alarms` |

The existing EMF log metrics (namespace `Fanout`) are untouched — they remain the real-AWS CloudWatch path; the flush now emits deltas over cumulative counters and runs every `METRICS_FLUSH_SECONDS` (5 in compose, 60 default).

## Evidence (all captured 2026-10-27, 300-job publish, SIM_LATENCY_MS=3000, MID scenario)

![Grafana live dashboard](grafana.png)
Queue in-flight 82, worker at 79% of permits, downstream peak 87 concurrent, drain rate visible, DLQ 0, duplicate downstream calls 0, 915 published.

![Live ops panel](live-dashboard.png)
`dashboard.html` mid-drain: 90 of 100 permits busy, queue draining, DLQ green.

![Jaeger traces](jaeger-traces.png)
Jaeger: per-message spans (`Sqs.DeleteMessage`, receive, DynamoDB, downstream HTTP) on `fanout-worker`.

Correctness under this run: 300/300 done, `duplicate_calls_total` stayed 0, DLQ stayed 0.

## Run it

```sh
docker compose -f infra/docker-compose.yml up -d          # backend+grafana+prometheus+jaeger+frontend
(cd infra/terraform/envs/local && terraform apply -auto-approve)   # queues/tables/alarms
# publish a batch (see README "Run it", or the button on the harness page) and open:
#   http://localhost:3000/d/fanout-load   (Grafana, anonymous)
#   http://localhost:5173/dashboard.html  (live panel)
#   http://localhost:16686                (Jaeger traces)
```

Demo knob: `SIM_LATENCY_MS=3000 docker compose -f infra/docker-compose.yml up -d --force-recreate backend` makes the ~1 min engagement load time-compressed to 3 s so the graphs visibly climb during a 300-job publish.

## Findings worth saying out loud (staff-engineer part)

1. **Counter reset bug found by the scrape.** `flushMetrics` used `sumThenReset` — every EMF flush zeroed the same counters the scrape endpoint reads, so Prometheus would have seen a counter reset every 5 s (garbage rates). Fixed by keeping counters cumulative and computing flush deltas (`FanOutWorker.flushMetrics`).
2. **Gauge semantics.** `worker in-flight` from the permit semaphore reads ~10/100 on an *idle* system: the poll loop holds the next batch's slots during the SQS long-poll (by design — a bounded pull). The scrape therefore reports `fanout_worker_inflight` as jobs inside `process()`; the slots view stays on the EMF line.
3. **LocalStack limits.** Community LocalStack accepts the CloudWatch alarms but not `PutDashboard` (the alarms module takes `enable_dashboard = false` locally; real AWS gets both). The `cloudwatch` endpoint override had to be added to the Terraform providers — without it the calls went to real AWS and failed with 403.
4. **Scrape cost.** `/prom` calls SQS attributes (2) + a registry COUNT scan per scrape. At 5 s this is a demo/dev choice; in production the EMF path (agentless, 1 log line per flush) is the primary metrics path and the scrape can back off to 60 s.

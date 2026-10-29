# Runbook — fan-out worker (what on-call does when an alarm fires)

Dashboards: Grafana `http://<grafana>/d/fanout-load` (local dev) / the per-region CloudWatch dashboard.
API: ops API `:8080` (offer status `GET /admin/offers/{id}/status`, queue depth in `GET /metrics`).

## Alarms -> actions

### `${region}-dlq-not-empty` and `${region}-worker-dead-lettered`
**Meaning:** jobs exhausted retries (or crashed fatally) and landed in the DLQ. Nothing is lost — the DLQ is the safety net.
1. Count and sample: `aws sqs receive-message --queue-url <dlq>` (message attribute `reason` carries why).
2. Diagnose by reason:
   - `503` / transient downstream → downstream was down for > all retries. Fix downstream, then **redrive** the DLQ (SQS "Start DLQ redrive", or republish the offer — idempotency makes redelivery safe: already-done jobs are skipped, never re-called).
   - `withdrawn`/`gone`/bad-payload (non-retryable) → bug or a stale consumer; the job was skipped to DLQ to stop a poison loop. Inspect the body, fix the producer, redrive.
3. Confirm: DLQ visible returns to 0, `fanout_downstream_duplicate_calls_total` stays flat (idempotency held).
**Never** delete DLQ messages to silence the alarm: that is silent data loss of fan-outs.

### `${region}-backlog-age`
**Meaning:** oldest job > 600 s in the work queue — the worker stopped draining.
1. Is the worker up? Task count in ECS; `/health` on the ops API.
2. Worker up but not draining → check Grafana **worker in-flight vs permits** and logs:
   - `breaker` events + `poll_failed` → downstream capacity problem (breaker open is correct behavior; fix downstream or raise `BREAKER_COOLDOWN_MS` patience, not the permits).
   - Nothing in logs, in-flight 0, queue full → worker cannot reach SQS/DynamoDB (network, credentials, region routing).
3. Worker down / crash loop → `restart: on-failure` in compose / ECS restarts it; the queue absorbs the outage (messages wait, no loss). After restart, watch the drain-rate panel — the backlog should slope to zero; no manual action.
4. Sustained overload (drain rate << arrival rate for hours) → scale out tasks (see limits below), or raise `SCENARIO` permits if the downstream quota actually grew.

## Known limits to say before acting
- Per-firm limiter and counters are per process: running 2+ tasks per region splits the firm cap and breaks "one global permit pool". Scaling out needs the shared limiter first (see PRESENTATION.md gaps).
- Visibility timeout is 2x the lease by design; if you raise `VISIBILITY_TIMEOUT` in Terraform, raise the lease (`VISIBILITY_TIMEOUT` env drives both) with it.
- A redelivered message is always safe (idempotency), so the bias when unsure is: restart the worker, let the queue re-deliver.

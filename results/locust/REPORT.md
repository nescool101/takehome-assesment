# Load results (Locust, time-compressed), cache off vs on

Downstream call compressed 60 s -> 300 ms (x200); engagement counts cut by 100 (giant firm 40,000 -> 400 jobs).
Little's Law check: predicted drain = max(jobs x latency / permits, giant jobs x latency / (permits / 2)).
Cache = in-process 5 s TTL cache of the "offer withdrawn?" read (`CACHE_ENABLED`).

## Drain and correctness

| Scenario | Cache | Permits | Jobs | Measured drain (s) | Predicted (s) | Measured / predicted | Peak concurrent <= permits | Duplicate downstream calls | DLQ | Pass | Status p95 (ms) | Within target |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | 200 | 800 | 6.9 | 1.2 | 5.75 | 73 (yes) | 0 | 0 | yes | 150 | NO (target 100) |
| OPTIMISTIC | on | 200 | 800 | 7.45 | 1.2 | 6.21 | 79 (yes) | 0 | 0 | yes | 410 | NO (target 100) |
| MID | off | 100 | 800 | 10.05 | 2.4 | 4.19 | 78 (yes) | 0 | 0 | yes | 35 | yes |
| MID | on | 100 | 800 | 12.05 | 2.4 | 5.02 | 66 (yes) | 0 | 0 | yes | 30 | yes |
| PESSIMISTIC | off | 50 | 4000 | 51.23 | 24.0 | 2.13 | 50 (yes) | 0 | 0 | yes | 140 | NO (target 100) |
| PESSIMISTIC | on | 50 | 4000 | 65.12 | 24.0 | 2.71 | 50 (yes) | 0 | 0 | yes | 88 | yes |

## Cache impact (same scenario, cache off vs on)

| Scenario | DynamoDB offer reads off | on | Saved | Drain off (s) | on (s) | Status p95 off (ms) | on (ms) | Read cost saved per run at $0.25/M |
|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | 1355 | 88 | 94% | 6.9 | 7.45 | 150 | 410 | $0.000317 |
| MID | 1722 | 88 | 95% | 10.05 | 12.05 | 35 | 30 | $0.000409 |
| PESSIMISTIC | 9243 | 222 | 98% | 51.23 | 65.12 | 140 | 88 | $0.002255 |

## Pending-badge latency for a median-size firm during the burst (seconds from publish start)

| Scenario | Cache | Badge visible after (s) | Whole burst drained after (s) |
|---|---|---|---|
| OPTIMISTIC | off | 5.83 | 6.9 |
| OPTIMISTIC | on | 4.56 | 7.45 |
| MID | off | 3.57 | 10.05 |
| MID | on | 3.41 | 12.05 |
| PESSIMISTIC | off | 3.14, 11.91, 22.51, 31.02, 40.51 | 51.23 |
| PESSIMISTIC | on | 1.98, 7.76, 17.12, 24.22, 31.89 | 65.12 |

## API latency under load (ms, from the Locust CSV)

| Scenario | Cache | Endpoint | Requests | Failures | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | /metrics | 1 | 0 | 24 | 24 | 24 |
| OPTIMISTIC | off | GET /engagements/[id]/status | 387 | 0 | 14 | 150 | 800 |
| OPTIMISTIC | off | POST /admin/publishes | 1 | 0 | 1900 | 1900 | 1900 |
| OPTIMISTIC | off | POST /engagements/[id]/decision | 49 | 0 | 2 | 5 | 7 |
| OPTIMISTIC | on | /metrics | 1 | 0 | 18 | 18 | 18 |
| OPTIMISTIC | on | GET /engagements/[id]/status | 395 | 0 | 10 | 410 | 770 |
| OPTIMISTIC | on | POST /admin/publishes | 1 | 0 | 940 | 940 | 940 |
| OPTIMISTIC | on | POST /engagements/[id]/decision | 37 | 0 | 2 | 5 | 5 |
| MID | off | /metrics | 1 | 0 | 22 | 22 | 22 |
| MID | off | GET /engagements/[id]/status | 400 | 0 | 13 | 35 | 86 |
| MID | off | POST /admin/publishes | 1 | 0 | 1600 | 1600 | 1600 |
| MID | off | POST /engagements/[id]/decision | 36 | 0 | 3 | 5 | 5 |
| MID | on | /metrics | 1 | 0 | 20 | 20 | 20 |
| MID | on | GET /engagements/[id]/status | 404 | 0 | 9 | 30 | 110 |
| MID | on | POST /admin/publishes | 1 | 0 | 1000 | 1000 | 1000 |
| MID | on | POST /engagements/[id]/decision | 29 | 0 | 3 | 5 | 6 |
| PESSIMISTIC | off | /metrics | 1 | 0 | 19 | 19 | 19 |
| PESSIMISTIC | off | GET /engagements/[id]/status | 387 | 0 | 28 | 140 | 210 |
| PESSIMISTIC | off | POST /admin/publishes | 5 | 0 | 650 | 740 | 740 |
| PESSIMISTIC | off | POST /engagements/[id]/decision | 40 | 0 | 2 | 4 | 5 |
| PESSIMISTIC | on | /metrics | 1 | 0 | 21 | 21 | 21 |
| PESSIMISTIC | on | GET /engagements/[id]/status | 384 | 0 | 19 | 88 | 160 |
| PESSIMISTIC | on | POST /admin/publishes | 5 | 0 | 280 | 590 | 590 |
| PESSIMISTIC | on | POST /engagements/[id]/decision | 45 | 0 | 2 | 4 | 5 |

## Extrapolated to the real system (60 s load, full scale, same permits and per-firm cap)

| Scenario | Burst jobs | Real drain |
|---|---|---|
| OPTIMISTIC | 40,400 | 6.7 h (24,000 s) |
| MID | 40,400 | 13.3 h (48,000 s) |
| PESSIMISTIC | 202,000 | 133.3 h (480,000 s) |

Raw data: per-run `*-summary.json`, Locust `*_stats.csv`, `*.html`. Re-run with `locust/run.sh` then `summarize.py`.

Interpretation, limits and the price/quality recommendation: [ANALYSIS.md](ANALYSIS.md).

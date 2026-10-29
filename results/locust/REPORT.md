# Load results (Locust, time-compressed), cache off vs on

Downstream call compressed 60 s -> 300 ms (x200); engagement counts cut by 100 (giant firm 40,000 -> 400 jobs).
Little's Law check: predicted drain = max(jobs x latency / permits, giant jobs x latency / (permits / 2)).
Cache = in-process 5 s TTL cache of the "offer withdrawn?" read (`CACHE_ENABLED`).

## Drain and correctness

| Scenario | Cache | Permits | Jobs | Measured drain (s) | Predicted (s) | Measured / predicted | Peak concurrent <= permits | Duplicate downstream calls | DLQ | Pass | Status p95 (ms) | Within target |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | 200 | 800 | 12.07 | 1.2 | 10.06 | 86 (yes) | 0 | 0 | yes | 650 | NO (target 100) |
| OPTIMISTIC | on | 200 | 800 | 13.85 | 1.2 | 11.54 | 60 (yes) | 0 | 0 | yes | 1200 | NO (target 100) |
| MID | off | 100 | 800 | 12.4 | 2.4 | 5.17 | 72 (yes) | 0 | 0 | yes | 190 | NO (target 100) |
| MID | on | 100 | 800 | 11.68 | 2.4 | 4.86 | 58 (yes) | 0 | 0 | yes | 160 | NO (target 100) |
| PESSIMISTIC | off | 50 | 3200 | 87.87 | 19.2 | 4.58 | 50 (yes) | 0 | 0 | yes | 420 | NO (target 100) |
| PESSIMISTIC | on | 50 | 3200 | 72.11 | 19.2 | 3.76 | 49 (yes) | 0 | 0 | yes | 300 | NO (target 100) |

## Cache impact (same scenario, cache off vs on)

| Scenario | DynamoDB offer reads off | on | Saved | Drain off (s) | on (s) | Status p95 off (ms) | on (ms) | Read cost saved per run at $0.25/M |
|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | 1380 | 28 | 98% | 12.07 | 13.85 | 650 | 1200 | $0.000338 |
| MID | 1680 | 42 | 98% | 12.4 | 11.68 | 190 | 160 | $0.000409 |
| PESSIMISTIC | 9984 | 205 | 98% | 87.87 | 72.11 | 420 | 300 | $0.002445 |

## Pending-badge latency for a median-size firm during the burst (seconds from publish start)

| Scenario | Cache | Badge visible after (s) | Whole burst drained after (s) |
|---|---|---|---|
| OPTIMISTIC | off | 12.08 | 12.07 |
| OPTIMISTIC | on | 13.86 | 13.85 |
| MID | off | 12.42 | 12.4 |
| MID | on | 10.66 | 11.68 |
| PESSIMISTIC | off | 10.28, 22.57, 34.19, 44.24 | 87.87 |
| PESSIMISTIC | on | 13.92, 24.17, 33.44, 43.67 | 72.11 |

## API latency under load (ms, from the Locust CSV)

| Scenario | Cache | Endpoint | Requests | Failures | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | /metrics | 1 | 0 | 57 | 57 | 57 |
| OPTIMISTIC | off | GET /engagements/[id]/status | 373 | 0 | 19 | 650 | 810 |
| OPTIMISTIC | off | POST /admin/publishes | 1 | 0 | 11000 | 11000 | 11000 |
| OPTIMISTIC | off | POST /engagements/[id]/decision | 37 | 0 | 35 | 80 | 130 |
| OPTIMISTIC | on | /metrics | 1 | 0 | 75 | 75 | 75 |
| OPTIMISTIC | on | GET /engagements/[id]/status | 355 | 0 | 16 | 1200 | 1500 |
| OPTIMISTIC | on | POST /admin/publishes | 1 | 0 | 13000 | 13000 | 13000 |
| OPTIMISTIC | on | POST /engagements/[id]/decision | 29 | 0 | 28 | 44 | 45 |
| MID | off | /metrics | 1 | 0 | 48 | 48 | 48 |
| MID | off | GET /engagements/[id]/status | 401 | 0 | 15 | 190 | 280 |
| MID | off | POST /admin/publishes | 1 | 0 | 11000 | 11000 | 11000 |
| MID | off | POST /engagements/[id]/decision | 19 | 0 | 26 | 130 | 130 |
| MID | on | /metrics | 1 | 0 | 52 | 52 | 52 |
| MID | on | GET /engagements/[id]/status | 399 | 0 | 14 | 160 | 280 |
| MID | on | POST /admin/publishes | 1 | 0 | 9600 | 9600 | 9600 |
| MID | on | POST /engagements/[id]/decision | 20 | 0 | 33 | 230 | 230 |
| PESSIMISTIC | off | /metrics | 1 | 0 | 65 | 65 | 65 |
| PESSIMISTIC | off | GET /engagements/[id]/status | 351 | 0 | 140 | 420 | 540 |
| PESSIMISTIC | off | POST /admin/publishes | 4 | 0 | 12000 | 12000 | 12000 |
| PESSIMISTIC | off | POST /engagements/[id]/decision | 19 | 0 | 370 | 790 | 790 |
| PESSIMISTIC | on | /metrics | 1 | 0 | 93 | 93 | 93 |
| PESSIMISTIC | on | GET /engagements/[id]/status | 366 | 0 | 86 | 300 | 500 |
| PESSIMISTIC | on | POST /admin/publishes | 4 | 0 | 11000 | 12000 | 12000 |
| PESSIMISTIC | on | POST /engagements/[id]/decision | 27 | 0 | 160 | 640 | 730 |

## Extrapolated to the real system (60 s load, full scale, same permits and per-firm cap)

| Scenario | Burst jobs | Real drain |
|---|---|---|
| OPTIMISTIC | 40,400 | 6.7 h (24,000 s) |
| MID | 40,400 | 13.3 h (48,000 s) |
| PESSIMISTIC | 161,600 | 106.7 h (384,000 s) |

Raw data: per-run `*-summary.json`, Locust `*_stats.csv`, `*.html`. Re-run with `locust/run.sh` then `summarize.py`.

Interpretation, limits and the price/quality recommendation: [ANALYSIS.md](ANALYSIS.md).

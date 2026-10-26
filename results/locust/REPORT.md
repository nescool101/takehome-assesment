# Load results (Locust, time-compressed), cache off vs on

Downstream call compressed 60 s -> 300 ms (x200); engagement counts cut by 100 (giant firm 40,000 -> 400 jobs).
Little's Law check: predicted drain = max(jobs x latency / permits, giant jobs x latency / (permits / 2)).
Cache = in-process 5 s TTL cache of the "offer withdrawn?" read (`CACHE_ENABLED`).

## Drain and correctness

| Scenario | Cache | Permits | Jobs | Measured drain (s) | Predicted (s) | Measured / predicted | Peak concurrent <= permits | Duplicate downstream calls | DLQ | Pass | Status p95 (ms) | Within target |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | 200 | 800 | 7.5 | 1.2 | 6.25 | 70 (yes) | 0 | 0 | yes | 330 | NO (target 100) |
| OPTIMISTIC | on | 200 | 800 | 5.47 | 1.2 | 4.56 | 90 (yes) | 0 | 0 | yes | 150 | NO (target 100) |
| MID | off | 100 | 800 | 10.52 | 2.4 | 4.38 | 72 (yes) | 0 | 0 | yes | 63 | yes |
| MID | on | 100 | 800 | 9.21 | 2.4 | 3.84 | 90 (yes) | 0 | 0 | yes | 54 | yes |
| PESSIMISTIC | off | 50 | 4000 | 58.95 | 24.0 | 2.46 | 50 (yes) | 0 | 0 | yes | 190 | NO (target 100) |
| PESSIMISTIC | on | 50 | 4000 | 61.87 | 24.0 | 2.58 | 50 (yes) | 0 | 0 | yes | 120 | NO (target 100) |

## Cache impact (same scenario, cache off vs on)

| Scenario | DynamoDB offer reads off | on | Saved | Drain off (s) | on (s) | Status p95 off (ms) | on (ms) | Read cost saved per run at $0.25/M |
|---|---|---|---|---|---|---|---|---|
| OPTIMISTIC | 1379 | 98 | 93% | 7.5 | 5.47 | 330 | 150 | $0.000320 |
| MID | 1636 | 48 | 97% | 10.52 | 9.21 | 63 | 54 | $0.000397 |
| PESSIMISTIC | 9764 | 201 | 98% | 58.95 | 61.87 | 190 | 120 | $0.002391 |

## Pending-badge latency for a median-size firm during the burst (seconds from publish start)

| Scenario | Cache | Badge visible after (s) | Whole burst drained after (s) |
|---|---|---|---|
| OPTIMISTIC | off | 6.41 | 7.5 |
| OPTIMISTIC | on | 4.39 | 5.47 |
| MID | off | 4.39 | 10.52 |
| MID | on | 3.24 | 9.21 |
| PESSIMISTIC | off | 3.34, 8.24, 14.63, 18.7, 23.58 | 58.95 |
| PESSIMISTIC | on | 1.65, 6.22, 10.35, 13.01, 17.49 | 61.87 |

## API latency under load (ms, from the Locust CSV)

| Scenario | Cache | Endpoint | Requests | Failures | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|
| OPTIMISTIC | off | /metrics | 1 | 0 | 25 | 25 | 25 |
| OPTIMISTIC | off | GET /engagements/[id]/status | 395 | 0 | 19 | 330 | 1200 |
| OPTIMISTIC | off | POST /admin/publishes | 1 | 0 | 2400 | 2400 | 2400 |
| OPTIMISTIC | off | POST /engagements/[id]/decision | 27 | 0 | 30 | 38 | 55 |
| OPTIMISTIC | on | /metrics | 1 | 0 | 19 | 19 | 19 |
| OPTIMISTIC | on | GET /engagements/[id]/status | 394 | 0 | 15 | 150 | 590 |
| OPTIMISTIC | on | POST /admin/publishes | 1 | 0 | 1400 | 1400 | 1400 |
| OPTIMISTIC | on | POST /engagements/[id]/decision | 28 | 0 | 30 | 39 | 40 |
| MID | off | /metrics | 1 | 0 | 18 | 18 | 18 |
| MID | off | GET /engagements/[id]/status | 398 | 0 | 16 | 63 | 130 |
| MID | off | POST /admin/publishes | 1 | 0 | 2200 | 2200 | 2200 |
| MID | off | POST /engagements/[id]/decision | 37 | 0 | 29 | 69 | 170 |
| MID | on | /metrics | 1 | 0 | 22 | 22 | 22 |
| MID | on | GET /engagements/[id]/status | 412 | 0 | 15 | 54 | 98 |
| MID | on | POST /admin/publishes | 1 | 0 | 1500 | 1500 | 1500 |
| MID | on | POST /engagements/[id]/decision | 36 | 0 | 28 | 83 | 160 |
| PESSIMISTIC | off | /metrics | 1 | 0 | 19 | 19 | 19 |
| PESSIMISTIC | off | GET /engagements/[id]/status | 385 | 0 | 46 | 190 | 260 |
| PESSIMISTIC | off | POST /admin/publishes | 5 | 0 | 440 | 1300 | 1300 |
| PESSIMISTIC | off | POST /engagements/[id]/decision | 31 | 0 | 66 | 250 | 250 |
| PESSIMISTIC | on | /metrics | 1 | 0 | 16 | 16 | 16 |
| PESSIMISTIC | on | GET /engagements/[id]/status | 385 | 0 | 29 | 120 | 180 |
| PESSIMISTIC | on | POST /admin/publishes | 5 | 0 | 350 | 830 | 830 |
| PESSIMISTIC | on | POST /engagements/[id]/decision | 42 | 0 | 57 | 130 | 210 |

## Extrapolated to the real system (60 s load, full scale, same permits and per-firm cap)

| Scenario | Burst jobs | Real drain |
|---|---|---|
| OPTIMISTIC | 40,400 | 6.7 h (24,000 s) |
| MID | 40,400 | 13.3 h (48,000 s) |
| PESSIMISTIC | 202,000 | 133.3 h (480,000 s) |

Raw data: per-run `*-summary.json`, Locust `*_stats.csv`, `*.html`. Re-run with `locust/run.sh` then `summarize.py`.

Interpretation, limits and the price/quality recommendation: [ANALYSIS.md](ANALYSIS.md).

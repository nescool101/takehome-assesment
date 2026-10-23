# Reading the load results (written by hand from `REPORT.md`, 2026-10-23)

One run per scenario and cache setting, against LocalStack on one laptop. Treat the numbers as evidence of behaviour, not benchmarks.

## What the runs show
- **Correctness held in all six runs:** peak concurrent downstream calls never exceeded the permit cap (73 of 200, 78 of 100, 50 of 50 with the cache off), zero duplicate downstream calls, empty DLQ.
- **Fairness held:** in PESSIMISTIC (a giant firm plus five simultaneous publishes) the first median firm's badge appeared after 3.1 s (cache off) and 2.0 s (cache on) while the giant firm was still draining. The fifth publish's median firm waited 40.5 s and 31.9 s, because later publishes queue behind earlier ones; the whole burst took 51 s and 65 s.
- **Little's Law is a lower bound, not a forecast.** Measured drain was 2.1x to 6.2x the prediction. In OPTIMISTIC and MID the worker never reached the permit cap (peak 66 to 79 against 100 to 200): at 300 ms per call, 200 permits means about 670 calls a second, and queue, DynamoDB and LocalStack overhead set the pace, not the permits. At the real 60 s call, 200 permits is about 3 calls a second, which is far below that overhead. So the extrapolated real-time drains in `REPORT.md` assume the permit cap is the bottleneck; that is plausible for the real system and not proven by this harness.
- **Publish itself is not instant here:** the publish request took up to about 2 s, and drain is measured from the start of publish.

## Cache off vs on
| | Result |
|---|---|
| DynamoDB offer reads | Down 94% to 98% (for example 9,243 to 222 in PESSIMISTIC) |
| Read cost saved | About $0.0003 to $0.002 per run, which is nothing |
| Drain time | Slower with the cache in all three scenarios (7.5 vs 6.9 s, 12.1 vs 10.1 s, 65.1 vs 51.2 s) |
| Status p95 | Mixed: worse in OPTIMISTIC (410 vs 150 ms), flat in MID, better in PESSIMISTIC (88 vs 140 ms) |
| Median-firm badge | Slightly earlier with the cache in OPTIMISTIC and PESSIMISTIC |

**Why we do not claim a win or a loss:** each cell is a single run, and every "cache on" run came after its "cache off" run on the same LocalStack, so run order is confounded with the cache setting. The consistent slowdown could be the cache or the order. A fair test alternates off, on, on, off and repeats each at least three times; we did not do that.

## Latency target (status read, 100 ms at p95)
Met in 3 of 6 runs (MID off and on, PESSIMISTIC on); missed in OPTIMISTIC (both) and PESSIMISTIC off. Each status call makes two sequential DynamoDB reads against LocalStack that is also serving the worker. Whether real DynamoDB would meet 100 ms is expected but not measured here. The assignment itself says "within seconds" for the indicator and gives no millisecond figure.

## Recommendation (price and quality, from these results)
1. **Ship with the cache off** (the default). It is free to run, but we measured no reliable speed benefit, and it adds a staleness rule (a withdrawal reaches a worker up to 5 s late). Turn it on only if the offers read shows up as a hot spot on real DynamoDB, and re-measure with an alternating test first.
2. **Do not buy DAX or an API Gateway cache.** The reads they would save cost under $9 a month in total, against about $88 per region for DAX and $14.60 per region for the gateway cache.
3. **Spend on the always-on workers and the gateway, not on caching.** Fargate is 48% to 60% of the TARGET cost and API Gateway 9% to 21%; see design doc section 3.
4. **Fix before production, in this order:** make the status read one call instead of two (a single DynamoDB item per engagement); measure against real DynamoDB; ask the downstream team for their real permit capacity, which dominates every drain time.

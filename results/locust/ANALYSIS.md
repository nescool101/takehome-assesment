# Reading the load results (written by hand from `REPORT.md`, 2026-10-26)

One run per scenario and cache setting, against LocalStack on one laptop. Treat the numbers as evidence of behaviour, not benchmarks.

## What the runs show
- **Correctness held in all six runs:** peak concurrent downstream calls never exceeded the permit cap (70 of 200, 72 of 100, 50 of 50 with the cache off), zero duplicate downstream calls, empty DLQ.
- **Fairness held:** in PESSIMISTIC (a giant firm plus five simultaneous publishes) the first median firm's badge appeared after 3.3 s (cache off) and 1.7 s (cache on) while the giant firm was still draining. The fifth publish's median firm waited 23.6 s and 17.5 s, because later publishes queue behind earlier ones; the whole burst took 59 s and 62 s.
- **Little's Law is a lower bound, not a forecast.** Measured drain was 2.5x to 6.3x the prediction. In OPTIMISTIC and MID the worker never reached the permit cap (peak 70 to 90 against 100 to 200): at 300 ms per call, 200 permits means about 670 calls a second, and queue, DynamoDB and LocalStack overhead set the pace, not the permits. At the real 60 s call, 200 permits is about 3 calls a second, which is far below that overhead. So the extrapolated real-time drains in `REPORT.md` assume the permit cap is the bottleneck; that is plausible for the real system and not proven by this harness.
- **Publish itself is not instant here:** the publish request took up to about 2 s, and drain is measured from the start of publish.

## Cache off vs on
| | Result |
|---|---|
| DynamoDB offer reads | Down 93% to 98% (for example 9,764 to 201 in PESSIMISTIC) |
| Read cost saved | About $0.0003 to $0.0024 per run, which is nothing |
| Drain time | Faster with the cache in OPTIMISTIC and MID (7.5 vs 5.5 s, 10.5 vs 9.2 s), slower in PESSIMISTIC (59.0 vs 61.9 s) |
| Status p95 | Better with the cache in all three here (330 vs 150 ms, 63 vs 54 ms, 190 vs 120 ms) |
| Median-firm badge | Earlier with the cache in all three (6.4 vs 4.4 s, 4.4 vs 3.2 s, 3.3 vs 1.7 s for the first median firm) |

**Why we do not claim a win or a loss:** each cell is a single run, and every "cache on" run came after its "cache off" run on the same LocalStack, so run order is confounded with the cache setting. The differences point both ways (faster in two scenarios, slower in one) and could be the cache or the order; the first 2026-10-23 set showed the opposite sign for drain in all three. A fair test alternates off, on, on, off and repeats each at least three times; we did not do that.

## Latency target (status read, 100 ms at p95)
Met in 2 of 6 runs (MID off and on); missed in OPTIMISTIC and PESSIMISTIC (both). Each status call makes two sequential DynamoDB reads against LocalStack that is also serving the worker. Whether real DynamoDB would meet 100 ms is expected but not measured here. The assignment itself says "within seconds" for the indicator and gives no millisecond figure.

## Recommendation (price and quality, from these results)
1. **Ship with the cache off** (the default). It is free to run, but we measured no reliable speed benefit, and it adds a staleness rule (a withdrawal reaches a worker up to 5 s late). Turn it on only if the offers read shows up as a hot spot on real DynamoDB, and re-measure with an alternating test first.
2. **Do not buy DAX or an API Gateway cache.** The reads they would save cost under $9 a month in total, against about $88 per region for DAX and $14.60 per region for the gateway cache.
3. **Spend on the always-on workers and the gateway, not on caching.** Fargate is 48% to 60% of the TARGET cost and API Gateway 9% to 21%; see design doc section 3.
4. **Fix before production, in this order:** make the status read one call instead of two (a single DynamoDB item per engagement); measure against real DynamoDB; ask the downstream team for their real permit capacity, which dominates every drain time.

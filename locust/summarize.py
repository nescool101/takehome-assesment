"""Merge the per-run summaries into results/locust/REPORT.md (linked from README, design doc and the PDF appendix)."""
import csv, glob, json, os

TARGET_MS = float(os.getenv("STATUS_P95_MS", "100"))  # latency target for the pending-badge read; not stretched to make runs pass

OUT = "../results/locust"
runs = {}  # (scenario, "off"|"on") -> (summary, locust stats by endpoint)
for f in sorted(glob.glob(f"{OUT}/*-summary.json")):
    s = json.load(open(f))
    stats = {}
    sf = f.replace("-summary.json", "_stats.csv")
    if os.path.exists(sf):
        stats = {r["Name"]: r for r in csv.DictReader(open(sf))}
    runs[(s["scenario"], "on" if s.get("cacheEnabled") else "off")] = (s, stats)

def p95(run):
    return run[1].get("GET /engagements/[id]/status", {}).get("95%", "n/a")


def within(run):
    v = p95(run)
    return "n/a" if v == "n/a" else ("yes" if float(v) <= TARGET_MS else f"NO (target {TARGET_MS:g})")


order = [(sc, c) for sc in ("OPTIMISTIC", "MID", "PESSIMISTIC") for c in ("off", "on") if (sc, c) in runs]
STATUS = "GET /engagements/[id]/status"
L = ["# Load results (Locust, time-compressed), cache off vs on", "",
     "Downstream call compressed 60 s -> 300 ms (x200); engagement counts cut by 100 (giant firm 40,000 -> 400 jobs).",
     "Little's Law check: predicted drain = max(jobs x latency / permits, giant jobs x latency / (permits / 2)).",
     "Cache = in-process 5 s TTL cache of the \"offer withdrawn?\" read (`CACHE_ENABLED`).", "",
     "## Drain and correctness", "",
     "| Scenario | Cache | Permits | Jobs | Measured drain (s) | Predicted (s) | Measured / predicted | Peak concurrent <= permits | Duplicate downstream calls | DLQ | Pass | Status p95 (ms) | Within target |",
     "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
for k in order:
    s = runs[k][0]
    L.append(f"| {k[0]} | {k[1]} | {s['permits']} | {s['jobs']} | {s['measuredDrainSeconds']} | {s['littleLawPredictedSeconds']} | "
             f"{s['measuredOverPredicted']} | {s['peakConcurrent']} ({'yes' if s['peakWithinPermits'] else 'NO'}) | "
             f"{s['duplicateDownstreamCalls']} | {s['dlq']['visible']} | {'yes' if s['pass'] else 'NO'} | {p95(runs[k])} | {within(runs[k])} |")

L += ["", "## Cache impact (same scenario, cache off vs on)", "",
      "| Scenario | DynamoDB offer reads off | on | Saved | Drain off (s) | on (s) | Status p95 off (ms) | on (ms) | Read cost saved per run at $0.25/M |",
      "|---|---|---|---|---|---|---|---|---|"]
for sc in ("OPTIMISTIC", "MID", "PESSIMISTIC"):
    if (sc, "off") in runs and (sc, "on") in runs:
        (a, sa), (b, sb) = runs[(sc, "off")], runs[(sc, "on")]
        ra, rb = a["catalogReads"], b["catalogReads"]
        pa, pb = sa.get(STATUS, {}).get("95%", "n/a"), sb.get(STATUS, {}).get("95%", "n/a")
        L.append(f"| {sc} | {ra} | {rb} | {100 * (ra - rb) / ra if ra else 0:.0f}% | {a['measuredDrainSeconds']} | {b['measuredDrainSeconds']} | "
                 f"{pa} | {pb} | ${(ra - rb) * 0.25 / 1e6:.6f} |")

L += ["", "## Pending-badge latency for a median-size firm during the burst (seconds from publish start)", "",
      "| Scenario | Cache | Badge visible after (s) | Whole burst drained after (s) |", "|---|---|---|---|"]
for k in order:
    s = runs[k][0]
    L.append(f"| {k[0]} | {k[1]} | {', '.join(str(v) for v in s['medianFirmBadgeSeconds'].values())} | {s['measuredDrainSeconds']} |")

L += ["", "## API latency under load (ms, from the Locust CSV)", "",
      "| Scenario | Cache | Endpoint | Requests | Failures | p50 | p95 | p99 |", "|---|---|---|---|---|---|---|---|"]
for k in order:
    for name, r in runs[k][1].items():
        if name != "Aggregated":
            L.append(f"| {k[0]} | {k[1]} | {name} | {r['Request Count']} | {r['Failure Count']} | {r['50%']} | {r['95%']} | {r['99%']} |")

L += ["", "## Extrapolated to the real system (60 s load, full scale, same permits and per-firm cap)", "",
      "| Scenario | Burst jobs | Real drain |", "|---|---|---|"]
for sc in ("OPTIMISTIC", "MID", "PESSIMISTIC"):
    k = next((k for k in order if k[0] == sc), None)
    if k:
        e = runs[k][0]["extrapolatedRealDrain"]
        L.append(f"| {sc} | {e['jobs']:,} | {e['hours']} h ({e['seconds']:,} s) |")
L += ["", "Raw data: per-run `*-summary.json`, Locust `*_stats.csv`, `*.html`. Re-run with `locust/run.sh` then `summarize.py`.", "", "Interpretation, limits and the price/quality recommendation: [ANALYSIS.md](ANALYSIS.md)."]
open(f"{OUT}/REPORT.md", "w").write("\n".join(L) + "\n")
print("\n".join(L))

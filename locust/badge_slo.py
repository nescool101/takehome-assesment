#!/usr/bin/env python3
"""Measures the user-visible SLO: publish -> badge appears (p50/p95/max).

Two probes:
  1. 25 solo publishes (1 engagement each): the fastest possible badge, no batch interference.
  2. 1 batch publish of 200 engagements: time until 50% / 95% of badges are visible (drain under load).

Usage: python3 badge_slo.py [API]   (default http://localhost:8080; backend must be up)
Writes: results/locust/badge-slo.json + prints a summary table.
"""
import json, statistics, sys, time, urllib.request

API = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

def call(method, path, body=None):
    req = urllib.request.Request(API + path, json.dumps(body).encode() if body else None,
                                 {"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def wait_badge(eng, deadline_s=60):
    t0 = time.time()
    while time.time() - t0 < deadline_s:
        if call("GET", f"/engagements/{eng}/status")["pending"]:
            return time.time() - t0
        time.sleep(0.05)
    return None

def pct(xs, p):
    xs = sorted(x for x in xs if x is not None)
    return xs[min(len(xs) - 1, int(len(xs) * p / 100))]

solo = []
for i in range(25):
    eng, ver = f"slo-{i}", f"v{i}-{int(time.time())}"
    call("POST", "/admin/publishes", {"templateId": "tpl-slo", "version": ver,
                                      "engagements": [{"id": eng, "firmId": f"f{i % 10}"}]})
    solo.append(wait_badge(eng))

BATCH = 200
batch_engs = [{"id": f"slo-b{i}", "firmId": f"f{i % 30}"} for i in range(BATCH)]
ver = f"vb-{int(time.time())}"
t0 = time.time()
call("POST", "/admin/publishes", {"templateId": "tpl-slo", "version": ver, "engagements": batch_engs})
appeared = {e["id"]: None for e in batch_engs}
while time.time() - t0 < 120 and any(v is None for v in appeared.values()):
    for eng, seen in appeared.items():
        if seen is None and call("GET", f"/engagements/{eng}/status")["pending"]:
            appeared[eng] = time.time() - t0
    time.sleep(0.1)

batch_times = [v for v in appeared.values() if v is not None]
out = {
    "solo_publishes": {"n": 25, "p50_s": round(pct(solo, 50), 3), "p95_s": round(pct(solo, 95), 3),
                       "max_s": round(max(x or 0 for x in solo), 3), "missing": sum(1 for x in solo if x is None)},
    "batch_200": {"n": BATCH, "p50_s": round(pct(batch_times, 50), 3), "p95_s": round(pct(batch_times, 95), 3),
                  "max_s": round(max(batch_times or [0]), 3), "missing": BATCH - len(batch_times)},
    "sim_latency_ms": 300, "date": time.strftime("%F"),
}
json.dump(out, open("badge-slo.json", "w"), indent=1)
print(f"solo  publish->badge: p50 {out['solo_publishes']['p50_s']}s  p95 {out['solo_publishes']['p95_s']}s  max {out['solo_publishes']['max_s']}s  missing {out['solo_publishes']['missing']}")
print(f"batch 200          : p50 {out['batch_200']['p50_s']}s  p95 {out['batch_200']['p95_s']}s  max {out['batch_200']['max_s']}s  missing {out['batch_200']['missing']}")

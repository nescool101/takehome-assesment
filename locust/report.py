"""After locust: wait for every offer to drain, then write <SCENARIO>-<date>-summary.json. Usage: report.py <offers.json> [base]"""
import json, sys, time, urllib.error, urllib.request
from datetime import date

path = sys.argv[1]
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080"
d = json.load(open(path))
cfg, offers, t0 = d["cfg"], d["offers"], d["t0"]
def get(p, tries=5):
    for n in range(tries):
        try:
            return json.load(urllib.request.urlopen(base + p, timeout=30))
        except (urllib.error.URLError, TimeoutError) as e:  # HTTPError is a URLError; retry refused/timeout too
            body = e.read()[:300] if isinstance(e, urllib.error.HTTPError) else e  # surface the API's error body
            print("request failed", p, body, file=sys.stderr)
            if n == tries - 1:
                raise
            time.sleep(1)

done_at = {o["offerId"]: o["doneAt"] for o in offers if "doneAt" in o}  # recorded live by locustfile's watcher
badge_at = {o["offerId"]: o["badgeAt"] for o in offers if "badgeAt" in o}
deadline = time.time() + 600
while time.time() < deadline and (len(done_at) < len(offers) or len(badge_at) < len(offers)):
    for o in offers:
        i = o["offerId"]
        if i not in done_at:
            s = get(f"/admin/offers/{i}/status")
            if s["done"] == s["published"]:
                done_at[i] = time.time() - t0
        if i not in badge_at and get(f"/engagements/{o['probe']}/status")["pending"]:
            badge_at[i] = time.time() - t0
    time.sleep(1)

m = get("/metrics")
total = sum(o["jobs"] for o in offers)
giant = sum(o["giant"] for o in offers)
lat = cfg["simLatencyMs"] / 1000
permits = cfg["permits"]
firm_cap = max(1, permits // 2)
predicted = max(total * lat / permits, giant * lat / firm_cap)  # Little's Law, bounded by the giant firm's cap
measured = max(done_at.values()) if len(done_at) == len(offers) else None
full_giant = cfg["maxPerFirm"] * len(offers)  # one giant firm, all burst products
full_total = full_giant + cfg["medianPerFirm"] * 10 * len(offers)
full_secs = max(full_total * cfg["loadSeconds"] / permits, full_giant * cfg["loadSeconds"] / firm_cap)
ds = m["downstream"]
res = {
    "scenario": cfg["scenario"], "cacheEnabled": cfg.get("cacheEnabled", False), "catalogReads": m["catalogReads"], "date": str(date.today()), "permits": permits, "simLatencyMs": cfg["simLatencyMs"],
    "jobs": total, "offers": len(offers),
    "measuredDrainSeconds": round(measured, 2) if measured else None, "littleLawPredictedSeconds": round(predicted, 2),
    "measuredOverPredicted": round(measured / predicted, 2) if measured else None,
    "medianFirmBadgeSeconds": {k: round(v, 2) for k, v in badge_at.items()},
    "peakConcurrent": ds["peakConcurrent"], "peakWithinPermits": ds["peakConcurrent"] <= permits,
    "duplicateDownstreamCalls": ds["duplicateCalls"], "dlq": m["dlq"],
    "extrapolatedRealDrain": {"jobs": full_total, "seconds": round(full_secs), "hours": round(full_secs / 3600, 1),
                              "note": "full-scale burst at real 60s load, same permits and per-firm cap"},
}
res["pass"] = bool(measured and res["peakWithinPermits"] and ds["duplicateCalls"] == 0 and m["dlq"]["visible"] == 0)
out = path.replace("-offers.json", "-summary.json")
json.dump(res, open(out, "w"), indent=1)
print(json.dumps(res, indent=1))
sys.exit(0 if res["pass"] else 1)

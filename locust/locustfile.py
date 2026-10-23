"""Load sim against the ops API. Numbers come from the backend's /metrics "config" (one source of truth: the SCENARIO the backend runs).

Scale: engagement counts are cut by SCALE (default 100) and downstream latency is compressed
(SIM_LATENCY_MS vs the real LOAD_SECONDS). report.py checks the measured wall time against Little's Law
and extrapolates to full scale.
"""
import json, os, random, time, urllib.request
from datetime import date
import gevent
from locust import HttpUser, between, events, task

SCALE = int(os.getenv("SCALE", "100"))
OTHER_FIRMS = int(os.getenv("OTHER_FIRMS", "10"))
BURST = {"OPTIMISTIC": 1, "MID": 1, "PESSIMISTIC": 5}  # simultaneous product publishes (plan: burst for PESSIMISTIC)
OUT = os.getenv("OUT_DIR", "../results/locust")
RUN = str(int(time.time()))

state = {"cfg": None, "t0": None, "offers": [], "engagements": []}


def body(tpl, ver, jobs):
    return {"templateId": tpl, "version": ver, "engagements": [{"id": i, "firmId": f} for i, f in jobs]}


def watch(base, o):
    """Records when an offer fully drains and when a median firm's badge shows, so runs shorter than the drain still measure it."""
    get = lambda p: json.load(urllib.request.urlopen(base + p, timeout=30))
    while "doneAt" not in o or "badgeAt" not in o:
        gevent.sleep(1)
        try:
            if "doneAt" not in o:
                st = get(f"/admin/offers/{o['offerId']}/status")
                if st["done"] == st["published"]:
                    o["doneAt"] = time.time() - state["t0"]
            if "badgeAt" not in o and get(f"/engagements/{o['probe']}/status")["pending"]:
                o["badgeAt"] = time.time() - state["t0"]
        except Exception:
            pass  # transient; report.py finishes whatever is missing


class Publisher(HttpUser):
    """One user: fans out the weekly publishes (giant firm + many median firms) as a burst."""
    fixed_count = 1
    wait_time = between(1, 1)

    def on_start(self):
        cfg = self.client.get("/metrics", name="/metrics").json()["config"]
        state["cfg"] = cfg
        giant = max(1, cfg["maxPerFirm"] // SCALE)
        state["t0"] = time.time()
        for p in range(BURST[cfg["scenario"]]):
            tpl, ver = f"tpl-{p}", f"v{RUN}"
            jobs = [(f"{RUN}-giant-{i}", f"giant-{RUN}") for i in range(giant)]
            for k in range(OTHER_FIRMS):
                for i in range(cfg["medianPerFirm"]):
                    jobs.append((f"{RUN}-{p}-f{k}-{i}", f"{RUN}-f{k}"))
            r = self.client.post("/admin/publishes", json=body(tpl, ver, jobs), name="POST /admin/publishes")
            state["offers"].append({"offerId": r.json().get("offerId"), "jobs": len(jobs), "giant": giant, "probe": f"{RUN}-{p}-f0-0"})
            state["engagements"] += [j[0] for j in jobs]
            gevent.spawn(watch, self.host, state["offers"][-1])

    @task
    def idle(self):
        pass


class IndicatorPoller(HttpUser):
    """UI polling the pending badge (the latency that matters: seconds)."""
    weight = 10
    wait_time = between(0.5, 1.5)

    @task(10)
    def poll(self):
        eng = random.choice(state["engagements"]) if state["engagements"] else "none"
        self.client.get(f"/engagements/{eng}/status", name="GET /engagements/[id]/status")

    @task(1)
    def decide(self):
        if state["engagements"]:
            eng = random.choice(state["engagements"])
            self.client.post(f"/engagements/{eng}/decision", json={"action": "decline"}, name="POST /engagements/[id]/decision")


@events.test_stop.add_listener
def save(environment, **kw):
    os.makedirs(OUT, exist_ok=True)
    cfg = state["cfg"] or {}
    path = f"{OUT}/{cfg.get('scenario', 'UNKNOWN')}-cache-{'on' if cfg.get('cacheEnabled') else 'off'}-{date.today()}-offers.json"
    json.dump({"t0": state["t0"], "cfg": cfg, "offers": state["offers"]}, open(path, "w"), indent=1)

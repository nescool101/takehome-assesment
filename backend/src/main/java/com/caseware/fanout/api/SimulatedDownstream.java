package com.caseware.fanout.api;

import com.caseware.fanout.worker.Job;
import com.caseware.fanout.worker.Ports.DownstreamClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for the other team's ~1 min engagement load; latency is time-compressed via SIM_LATENCY_MS. Counts what the load test checks. */
public final class SimulatedDownstream implements DownstreamClient {
    private final long latencyMs;
    private final AtomicInteger now = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger();
    private final Map<String, AtomicInteger> perKey = new ConcurrentHashMap<>();

    public SimulatedDownstream(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    @Override
    public void load(Job job) {
        calls.incrementAndGet();
        perKey.computeIfAbsent(job.key(), k -> new AtomicInteger()).incrementAndGet();
        peak.accumulateAndGet(now.incrementAndGet(), Math::max);
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            now.decrementAndGet();
        }
    }

    /** JSON: calls, peak concurrent calls, duplicate calls (same key called more than once). */
    public String statsJson() {
        long dups = perKey.values().stream().mapToLong(c -> c.get() - 1).sum();
        return "{\"calls\":" + calls + ",\"peakConcurrent\":" + peak + ",\"inUse\":" + now + ",\"duplicateCalls\":" + dups + "}";
    }
}

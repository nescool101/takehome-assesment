package com.caseware.fanout.worker;

import com.caseware.fanout.worker.Ports.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Slow downstream stub: configurable latency/failures, records peak concurrency overall and per firm. */
class SimDownstream implements DownstreamClient {
    final long latencyMs;
    final Function<Job, DownstreamException> failure;
    final AtomicInteger calls = new AtomicInteger(), now = new AtomicInteger(), peak = new AtomicInteger();
    final Map<String, AtomicInteger> firmNow = new ConcurrentHashMap<>(), firmPeak = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> perKey = new ConcurrentHashMap<>();

    SimDownstream(long latencyMs, Function<Job, DownstreamException> failure) {
        this.latencyMs = latencyMs;
        this.failure = failure;
    }

    SimDownstream(long latencyMs) {
        this(latencyMs, j -> null);
    }

    @Override
    public void load(Job job) throws DownstreamException {
        calls.incrementAndGet();
        perKey.computeIfAbsent(job.key(), k -> new AtomicInteger()).incrementAndGet();
        peak.accumulateAndGet(now.incrementAndGet(), Math::max);
        int f = firmNow.computeIfAbsent(job.firmId(), k -> new AtomicInteger()).incrementAndGet();
        firmPeak.computeIfAbsent(job.firmId(), k -> new AtomicInteger()).accumulateAndGet(f, Math::max);
        try {
            Thread.sleep(latencyMs);
            DownstreamException e = failure.apply(job);
            if (e != null) throw e;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            now.decrementAndGet();
            firmNow.get(job.firmId()).decrementAndGet();
        }
    }
}

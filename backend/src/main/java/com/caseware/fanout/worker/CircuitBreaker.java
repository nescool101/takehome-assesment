package com.caseware.fanout.worker;

import java.util.function.LongSupplier;

/**
 * Opens after N consecutive failures for a cooldown; protects the other team's service. State lives in a
 * {@link BreakerStore} so all workers of a region trip and recover together. Reads are cached for {@code refreshMs};
 * the store is written only on failures, on the first success after failures, and when claiming a probe.
 */
public final class CircuitBreaker {
    private final int threshold;
    private final long cooldownMs, refreshMs;
    private final LongSupplier nowMs;
    private final BreakerStore store;
    private volatile int failures;
    private volatile long openUntil, lastRead = -1;

    public CircuitBreaker(int threshold, long cooldownMs, LongSupplier nowMs) {
        this(threshold, cooldownMs, nowMs, new MemBreakerStore(), 0);
    }

    public CircuitBreaker(int threshold, long cooldownMs, LongSupplier nowMs, BreakerStore store, long refreshMs) {
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.nowMs = nowMs;
        this.store = store;
        this.refreshMs = refreshMs;
    }

    /**
     * Closed: yes. Cooling down: no. After the cooldown (half-open): one probe per cooldown window across all workers;
     * its success closes the breaker, its failure re-opens it. A probe lost to a deferral just expires.
     */
    public boolean allow() {
        long now = nowMs.getAsLong();
        refresh(now);
        if (now < openUntil) return false;
        if (failures < threshold) return true;
        try {
            return store.claimProbe(now, now + cooldownMs);
        } catch (RuntimeException e) { // store down: stay closed to calls, the next refresh retries
            Log.event("warn", "breaker_store_failed", "op", "claimProbe", "error", e.toString());
            return false;
        }
    }

    /** Non-consuming peek for the poll loop: true while no call would be allowed through. */
    public boolean coolingDown() {
        long now = nowMs.getAsLong();
        refresh(now);
        return now < openUntil;
    }

    public void success() {
        if (failures == 0) return;
        try {
            store.reset();
            failures = 0;
        } catch (RuntimeException e) {
            Log.event("warn", "breaker_store_failed", "op", "reset", "error", e.toString());
        }
    }

    public void failure() {
        try {
            int n = store.addFailure();
            failures = n;
            if (n >= threshold) {
                long until = nowMs.getAsLong() + cooldownMs;
                store.open(until);
                openUntil = Math.max(openUntil, until);
            }
        } catch (RuntimeException e) { // store down: fall back to this worker's own count
            Log.event("warn", "breaker_store_failed", "op", "failure", "error", e.toString());
            if (++failures >= threshold) openUntil = nowMs.getAsLong() + cooldownMs;
        }
    }

    private void refresh(long now) {
        if (lastRead >= 0 && now - lastRead < refreshMs) return;
        lastRead = now;
        try {
            var s = store.read();
            failures = s.failures();
            openUntil = s.openUntil();
        } catch (RuntimeException e) { // keep the cached view
            Log.event("warn", "breaker_store_failed", "op", "read", "error", e.toString());
        }
    }
}

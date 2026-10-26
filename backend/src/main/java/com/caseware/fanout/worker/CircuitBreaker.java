package com.caseware.fanout.worker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Opens after N consecutive failures for a cooldown; protects the other team's service. */
public final class CircuitBreaker {
    private final int threshold;
    private final long cooldownMs;
    private final LongSupplier nowMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();
    private final AtomicLong nextProbe = new AtomicLong();

    public CircuitBreaker(int threshold, long cooldownMs, LongSupplier nowMs) {
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.nowMs = nowMs;
    }

    /**
     * Closed: yes. Cooling down: no. After the cooldown (half-open): one probe per cooldown window; its success closes
     * the breaker, its failure re-opens it. A probe lost to a deferral or non-breaker outcome just expires.
     */
    public boolean allow() {
        long now = nowMs.getAsLong();
        if (now < openUntil.get()) return false;
        if (consecutiveFailures.get() < threshold) return true;
        long p = nextProbe.get();
        return now >= p && nextProbe.compareAndSet(p, now + cooldownMs);
    }

    /** Non-consuming peek for the poll loop: true while no call would be allowed through. */
    public boolean coolingDown() {
        return nowMs.getAsLong() < openUntil.get();
    }

    public void success() {
        consecutiveFailures.set(0);
    }

    public void failure() {
        if (consecutiveFailures.incrementAndGet() >= threshold) {
            openUntil.set(nowMs.getAsLong() + cooldownMs);
        }
    }
}

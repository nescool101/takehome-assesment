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

    public CircuitBreaker(int threshold, long cooldownMs, LongSupplier nowMs) {
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.nowMs = nowMs;
    }

    // ponytail: after cooldown everything is let through (no single half-open probe); failures re-open at once.
    public boolean allow() {
        return nowMs.getAsLong() >= openUntil.get();
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

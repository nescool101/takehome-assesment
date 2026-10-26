package com.caseware.fanout.worker;

/** Single-process store: the default, and what tests use. */
public final class MemBreakerStore implements BreakerStore {
    private int failures;
    private long openUntil, nextProbe;

    public synchronized State read() {
        return new State(failures, openUntil);
    }

    public synchronized int addFailure() {
        return ++failures;
    }

    public synchronized void open(long untilMs) {
        openUntil = Math.max(openUntil, untilMs);
    }

    public synchronized void reset() {
        failures = 0;
    }

    public synchronized boolean claimProbe(long nowMs, long nextMs) {
        if (nowMs < nextProbe) return false;
        nextProbe = nextMs;
        return true;
    }
}

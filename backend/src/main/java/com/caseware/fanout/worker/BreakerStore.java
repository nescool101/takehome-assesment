package com.caseware.fanout.worker;

/** Breaker state shared by every worker of a region. Implementations must make each method atomic. */
public interface BreakerStore {
    record State(int failures, long openUntil) {}

    State read();

    /** Atomically add one consecutive failure; returns the new count. */
    int addFailure();

    /** Keep the later of the stored and given reopen time. */
    void open(long untilMs);

    void reset();

    /** True for exactly one caller while {@code nowMs} is past the stored next-probe time; that caller sets it to {@code nextMs}. */
    boolean claimProbe(long nowMs, long nextMs);
}

package com.caseware.fanout.worker;

import java.time.Duration;
import java.util.Random;

/** Exponential backoff with full jitter: uniform in [1ms, min(cap, base * 2^(attempt-1))]. */
public record Backoff(Duration base, Duration cap, Random random) {
    public Duration delay(int attempt) {
        long ceiling = Math.min(cap.toMillis(), base.toMillis() << Math.min(attempt - 1, 20));
        return Duration.ofMillis(1 + random.nextLong(Math.max(1, ceiling)));
    }
}

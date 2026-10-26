package com.caseware.fanout.worker;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-firm in-flight cap so a 40,000-engagement firm cannot take every downstream permit. The cap also shrinks to a fair
 * share ({@code permits / active firms}, never below 1) so several big firms cannot split all permits between them.
 * Shares apply to new acquisitions only; in-flight calls are never evicted.
 */
public final class FirmLimiter {
    private final int maxPerFirm, permits;
    private final Map<String, Integer> inFlight = new HashMap<>();

    public FirmLimiter(int maxPerFirm) {
        this(maxPerFirm, Integer.MAX_VALUE);
    }

    public FirmLimiter(int maxPerFirm, int permits) {
        this.maxPerFirm = maxPerFirm;
        this.permits = permits;
    }

    /** @return a release handle, or null when the firm is at its cap. */
    public synchronized Runnable tryAcquire(String firmId) {
        int mine = inFlight.getOrDefault(firmId, 0);
        int active = inFlight.size() + (mine == 0 ? 1 : 0);
        int cap = Math.min(maxPerFirm, Math.max(1, permits / Math.max(2, active)));
        if (mine >= cap) return null;
        inFlight.merge(firmId, 1, Integer::sum);
        return () -> release(firmId);
    }

    private synchronized void release(String firmId) {
        inFlight.computeIfPresent(firmId, (k, v) -> v > 1 ? v - 1 : null);
    }
}

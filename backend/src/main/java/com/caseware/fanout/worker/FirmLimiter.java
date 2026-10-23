package com.caseware.fanout.worker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Per-firm in-flight cap so a 40,000-engagement firm cannot take every downstream permit. */
public final class FirmLimiter {
    private final int perFirmCap;
    private final ConcurrentHashMap<String, Semaphore> firms = new ConcurrentHashMap<>();

    public FirmLimiter(int perFirmCap) {
        this.perFirmCap = perFirmCap;
    }

    /** @return a release handle, or null when the firm is at its cap. */
    public Runnable tryAcquire(String firmId) {
        Semaphore s = firms.computeIfAbsent(firmId, k -> new Semaphore(perFirmCap));
        return s.tryAcquire() ? s::release : null;
    }
}

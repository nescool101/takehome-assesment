package com.caseware.fanout.worker;

import com.caseware.fanout.worker.Outcome.*;
import com.caseware.fanout.worker.Ports.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * Publish fan-out worker. Global in-flight is bounded by {@code permits} (the downstream quota),
 * per-firm by {@link FirmLimiter}. Virtual threads wait on the slow call; the permit count, not the
 * thread count, is the limit. Delivery is at-least-once; effects are idempotent via {@link IdempotencyStore}.
 */
public final class FanOutWorker {
    private final JobSource source;
    private final DownstreamClient downstream;
    private final IdempotencyStore idem;
    private final OfferCatalog catalog;
    private final FirmLimiter firms;
    private final CircuitBreaker breaker;
    private final Backoff backoff;
    private final int maxAttempts;
    private final Duration deferDelay;
    private final Semaphore slots;

    public FanOutWorker(JobSource source, DownstreamClient downstream, IdempotencyStore idem, OfferCatalog catalog,
                        FirmLimiter firms, CircuitBreaker breaker, Backoff backoff,
                        int permits, int maxAttempts, Duration deferDelay) {
        this.source = source;
        this.downstream = downstream;
        this.idem = idem;
        this.catalog = catalog;
        this.firms = firms;
        this.breaker = breaker;
        this.backoff = backoff;
        this.maxAttempts = maxAttempts;
        this.deferDelay = deferDelay;
        this.slots = new Semaphore(permits);
    }

    /** Poll loop. Never holds more than {@code permits} messages: pulls only as many as there are free slots. */
    public void run(BooleanSupplier stop, int batch, Duration idle) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!stop.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                if (!breaker.allow()) { // breaker open: leave messages on the queue instead of receive+resend+delete per message
                    sleep(idle);
                    continue;
                }
                int want = Math.min(batch, slots.availablePermits());
                List<Received> got = List.of();
                if (want > 0 && slots.tryAcquire(want)) {
                    try {
                        got = source.poll(want);
                    } catch (RuntimeException e) { // transient SQS error: keep the loop alive
                        System.err.println("poll failed: " + e);
                    }
                    slots.release(want - got.size()); // return unused slots
                }
                if (got.isEmpty()) {
                    sleep(idle);
                    continue;
                }
                for (Received r : got) {
                    pool.submit(() -> {
                        try {
                            process(r);
                        } catch (RuntimeException e) { // message stays unacked: redelivered after the visibility timeout
                            System.err.println("process failed for " + r.job().key() + ": " + e);
                        } finally {
                            slots.release();
                        }
                    });
                }
            }
        } // close() waits for in-flight work
    }

    public Outcome process(Received r) {
        Job job = r.job();
        if (catalog.isWithdrawn(job.templateId(), job.version())) {
            source.ack(r);
            return new Skipped("withdrawn");
        }
        if (!breaker.allow()) return defer(r, "breaker-open");
        Runnable releaseFirm = firms.tryAcquire(job.firmId());
        if (releaseFirm == null) return defer(r, "firm-cap");
        try {
            return switch (idem.begin(job.key())) {
                case DONE -> {
                    source.ack(r);
                    yield new Skipped("duplicate");
                }
                case IN_PROGRESS -> defer(r, "in-progress");
                case STARTED -> call(r);
            };
        } finally {
            releaseFirm.run();
        }
    }

    private Outcome call(Received r) {
        Job job = r.job();
        try {
            downstream.load(job);
        } catch (DownstreamException e) {
            return failed(r, e.getMessage(), e.retryable);
        } catch (RuntimeException e) {
            return failed(r, String.valueOf(e), true);
        }
        // load succeeded: a bookkeeping error here must not release the key or count as a downstream failure
        breaker.success();
        markDone(job.key());
        source.ack(r); // crash before this line => redelivery => DONE => skipped
        return new Done();
    }

    /** Retry in-process: a lost done() leaves the claim IN_PROGRESS until the lease expires, then the 1 min call repeats. */
    private void markDone(String key) {
        for (int i = 1; ; i++) {
            try {
                idem.done(key);
                return;
            } catch (RuntimeException e) {
                if (i == 3) throw e;
                sleep(Duration.ofMillis(100L << i));
            }
        }
    }

    private Outcome failed(Received r, String why, boolean retryable) {
        idem.release(r.job().key());
        if (retryable) breaker.failure();
        if (!retryable || r.attempt() >= maxAttempts) {
            source.deadLetter(r, why);
            return new DeadLettered(why);
        }
        source.retry(r, backoff.delay(r.attempt()));
        return new Retried(r.attempt());
    }

    private Outcome defer(Received r, String why) {
        source.defer(r, deferDelay);
        return new Deferred(why);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.caseware.fanout.worker;

import com.caseware.fanout.worker.Outcome.*;
import com.caseware.fanout.worker.Ports.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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
    private final int permits;
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final Map<String, Long> flushed = new ConcurrentHashMap<>(); // counter values already emitted by flushMetrics
    private final AtomicInteger busy = new AtomicInteger(); // tasks inside process() right now

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
        this.permits = permits;
        this.slots = new Semaphore(permits);
    }

    /** Poll loop. Never holds more than {@code permits} messages: pulls only as many as there are free slots. */
    public void run(BooleanSupplier stop, int batch, Duration idle) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!stop.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                if (breaker.coolingDown()) { // breaker open: leave messages on the queue instead of receive+resend+delete per message
                    sleep(idle);
                    continue;
                }
                int want = Math.min(batch, slots.availablePermits());
                List<Received> got = List.of();
                if (want > 0 && slots.tryAcquire(want)) {
                    try {
                        got = source.poll(want);
                    } catch (RuntimeException e) { // transient SQS error: keep the loop alive
                        Log.event("error", "poll_failed", "error", e.toString());
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
                            Log.event("error", "process_failed", "job", r.job().key(), "error", e.toString());
                        } finally {
                            slots.release();
                        }
                    });
                }
            }
        } // close() waits for in-flight work
    }

    /** Emit the outcome counters since the last flush (deltas over the cumulative {@link #counts}), plus in-flight, as one EMF line. */
    public void flushMetrics() {
        var m = new java.util.LinkedHashMap<String, Long>();
        for (String k : List.of("Done", "Skipped", "Deferred", "Retried", "DeadLettered")) {
            long total = count(k);
            m.put(k, total - flushed.getOrDefault(k, 0L));
            flushed.put(k, total);
        }
        m.put("InFlight", (long) (permits - slots.availablePermits()));
        Log.emf(m);
    }

    /** Cumulative counters (never reset, safe to read concurrently with flushMetrics) + jobs in process(), for the ops API scrape endpoints. */
    public Map<String, Long> snapshotMetrics() {
        var m = new java.util.LinkedHashMap<String, Long>();
        for (String k : List.of("Done", "Skipped", "Deferred", "Retried", "DeadLettered")) m.put(k, count(k));
        m.put("InFlight", (long) busy.get()); // the slots gauge would show the poll batch reserve, not work
        return m;
    }

    private long count(String k) {
        var a = counts.get(k);
        return a == null ? 0L : a.sum();
    }

    public Outcome process(Received r) {
        busy.incrementAndGet();
        try {
            Outcome o = handle(r);
            counts.computeIfAbsent(o.getClass().getSimpleName(), k -> new LongAdder()).increment();
            if (o instanceof DeadLettered d) Log.event("warn", "dead_lettered", "job", r.job().key(), "reason", d.reason());
            return o;
        } finally {
            busy.decrementAndGet();
        }
    }

    private Outcome handle(Received r) {
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

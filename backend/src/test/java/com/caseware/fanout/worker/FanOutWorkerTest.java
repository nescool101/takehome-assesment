package com.caseware.fanout.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.caseware.fanout.worker.Ports.DownstreamException;
import java.time.Duration;
import java.util.Random;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class FanOutWorkerTest {
    private final InMemorySource source = new InMemorySource();

    private FanOutWorker worker(SimDownstream d, int permits, int firmCap, int maxAttempts, BiPredicate<String, String> withdrawn) {
        return new FanOutWorker(source, d, new MemIdempotency(), withdrawn::test, new FirmLimiter(firmCap),
                new CircuitBreaker(1_000, 1, System::currentTimeMillis),
                new Backoff(Duration.ofMillis(2), Duration.ofMillis(20), new Random(1)),
                permits, maxAttempts, Duration.ofMillis(2));
    }

    private void drain(FanOutWorker w) {
        long deadline = System.currentTimeMillis() + 10_000; // a broken worker must fail, not hang
        w.run(() -> source.drained() || System.currentTimeMillis() > deadline, 50, Duration.ofMillis(2));
        assertTrue(source.drained(), "queue not drained within 10s");
    }

    private static Job job(String eng, String firm) {
        return new Job(eng, firm, "tpl-audit", "v7");
    }

    @Test
    void concurrencyNeverExceedsPermitsAndEveryJobRunsOnce() {
        SimDownstream d = new SimDownstream(20);
        for (int i = 0; i < 200; i++) source.send(job("e" + i, "f" + (i % 17)));
        drain(worker(d, 10, 10, 3, (t, v) -> false));
        assertEquals(200, d.calls.get());
        assertTrue(d.peak.get() <= 10, "peak " + d.peak.get());
        assertTrue(d.peak.get() > 1, "should actually run in parallel");
        assertTrue(d.perKey.values().stream().allMatch(c -> c.get() == 1));
        assertTrue(source.dlq.isEmpty());
    }

    @Test
    void redeliveredDuplicatesRunOnce() {
        SimDownstream d = new SimDownstream(30);
        for (int i = 0; i < 5; i++) source.send(job("same", "f1")); // 5 copies, even concurrently in one batch
        drain(worker(d, 10, 10, 3, (t, v) -> false));
        assertEquals(1, d.calls.get());
    }

    @Test
    void transientFailureRetriesThenDeadLetters() {
        SimDownstream d = new SimDownstream(1, j -> new DownstreamException("503", true));
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 3, (t, v) -> false));
        assertEquals(3, d.calls.get());
        assertEquals(1, source.dlq.size());
    }

    @Test
    void transientFailureThenSuccessIsApplied() {
        var n = new java.util.concurrent.atomic.AtomicInteger();
        SimDownstream d = new SimDownstream(1, j -> n.incrementAndGet() < 3 ? new DownstreamException("429", true) : null);
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 5, (t, v) -> false));
        assertEquals(3, d.calls.get());
        assertTrue(source.dlq.isEmpty());
    }

    @Test
    void fatalFailureDeadLettersImmediately() {
        SimDownstream d = new SimDownstream(1, j -> new DownstreamException("gone", false));
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 3, (t, v) -> false));
        assertEquals(1, d.calls.get());
        assertEquals(1, source.dlq.size());
    }

    @Test
    void withdrawnVersionIsSkipped() {
        SimDownstream d = new SimDownstream(1);
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 3, (t, v) -> v.equals("v7")));
        assertEquals(0, d.calls.get());
        assertTrue(source.dlq.isEmpty());
    }

    @Test
    void deferralsDoNotConsumeAttempts() {
        // 1 firm, cap 1, 6 jobs, maxAttempts 1: heavy deferral must not dead-letter anything
        SimDownstream d = new SimDownstream(5);
        for (int i = 0; i < 6; i++) source.send(job("e" + i, "f1"));
        drain(worker(d, 6, 1, 1, (t, v) -> false));
        assertEquals(6, d.calls.get());
        assertTrue(source.dlq.isEmpty());
    }

    @Test
    void giantFirmCannotTakeAllPermits() {
        SimDownstream d = new SimDownstream(15);
        for (int i = 0; i < 60; i++) source.send(job("big" + i, "giant"));
        for (int f = 0; f < 5; f++) for (int i = 0; i < 4; i++) source.send(job("s" + f + "-" + i, "small" + f));
        drain(worker(d, 10, 3, 3, (t, v) -> false));
        assertEquals(80, d.calls.get());
        assertTrue(d.firmPeak.get("giant").get() <= 3, "giant peak " + d.firmPeak.get("giant"));
        assertTrue(d.peak.get() <= 10);
    }

    @Test
    void breakerOpensAfterThresholdAndRecoversAfterCooldown() {
        long[] t = {0};
        CircuitBreaker b = new CircuitBreaker(3, 100, () -> t[0]);
        b.failure(); b.failure();
        assertTrue(b.allow());
        b.failure();
        assertFalse(b.allow());
        t[0] = 100;
        assertTrue(b.allow());
        b.success();
        b.failure();
        assertTrue(b.allow()); // counter was reset by success
    }

    @Test
    void halfOpenLetsOneProbeThroughPerCooldown() {
        long[] t = {0};
        CircuitBreaker b = new CircuitBreaker(1, 100, () -> t[0]);
        b.failure();
        assertTrue(b.coolingDown());
        t[0] = 100;
        assertFalse(b.coolingDown());
        assertTrue(b.allow());  // the probe
        assertFalse(b.allow()); // everyone else waits
        b.failure();            // probe failed: open again
        assertFalse(b.allow());
        t[0] = 200;
        assertTrue(b.allow());
        b.success();
        assertTrue(b.allow() && b.allow()); // closed
    }

    @Test
    void firmShareShrinksWhenMoreFirmsAreActive() {
        var l = new FirmLimiter(5, 6);
        assertNotNull(l.tryAcquire("a")); // alone: cap min(5, 6/2) = 3
        assertNotNull(l.tryAcquire("a"));
        assertNotNull(l.tryAcquire("a"));
        assertNull(l.tryAcquire("a"));
        assertNotNull(l.tryAcquire("b")); // 2 active: share 3
        assertNotNull(l.tryAcquire("c")); // 3 active: share 2, c has none
        assertNotNull(l.tryAcquire("c"));
        assertNull(l.tryAcquire("c"));
    }

    @Test
    void backoffStaysWithinCap() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(5), new Random(7));
        for (int a = 1; a < 40; a++) {
            long ms = b.delay(a).toMillis();
            assertTrue(ms >= 1 && ms <= 5_000, "attempt " + a + " -> " + ms);
        }
    }

    @Test
    void pollErrorDoesNotKillTheLoop() {
        SimDownstream d = new SimDownstream(1);
        source.failPolls = 3;
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 3, (t, v) -> false));
        assertEquals(1, d.calls.get());
    }

    @Test
    void ackFailureAfterSuccessfulLoadDoesNotRepeatTheLoad() {
        SimDownstream d = new SimDownstream(1);
        source.failAcks = 1;
        source.send(job("e1", "f1"));
        drain(worker(d, 4, 4, 3, (t, v) -> false));
        assertEquals(1, d.calls.get()); // redelivery sees DONE and skips
        assertTrue(source.dlq.isEmpty());
    }
}

package com.caseware.fanout.worker;

import com.caseware.fanout.worker.Ports.*;
import java.time.Duration;
import java.util.*;

/** Test double with SQS-like semantics: visibility delays, in-flight tracking, DLQ. */
class InMemorySource implements JobSource {
    private record Entry(Job job, int failures, long visibleAtMs) {}

    private final List<Entry> queue = new ArrayList<>();
    private final Set<Received> inflight = new HashSet<>();
    final List<String> dlq = Collections.synchronizedList(new ArrayList<>());

    synchronized void send(Job job) {
        queue.add(new Entry(job, 0, 0));
    }

    @Override
    public synchronized List<Received> poll(int max) {
        long now = System.currentTimeMillis();
        List<Received> out = new ArrayList<>();
        for (Iterator<Entry> it = queue.iterator(); it.hasNext() && out.size() < max; ) {
            Entry e = it.next();
            if (e.visibleAtMs <= now) {
                it.remove();
                Received r = new Received(e.job, e.failures + 1, e);
                inflight.add(r);
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public synchronized void ack(Received r) {
        inflight.remove(r);
    }

    @Override
    public synchronized void defer(Received r, Duration delay) {
        requeue(r, ((Entry) r.handle()).failures, delay);
    }

    @Override
    public synchronized void retry(Received r, Duration delay) {
        requeue(r, ((Entry) r.handle()).failures + 1, delay);
    }

    @Override
    public synchronized void deadLetter(Received r, String reason) {
        inflight.remove(r);
        dlq.add(r.job().key() + ":" + reason);
    }

    private void requeue(Received r, int failures, Duration delay) {
        inflight.remove(r);
        queue.add(new Entry(r.job(), failures, System.currentTimeMillis() + delay.toMillis()));
    }

    synchronized boolean drained() {
        return queue.isEmpty() && inflight.isEmpty();
    }
}

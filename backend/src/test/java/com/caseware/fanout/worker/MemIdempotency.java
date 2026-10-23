package com.caseware.fanout.worker;

import java.util.concurrent.ConcurrentHashMap;

class MemIdempotency implements Ports.IdempotencyStore {
    private final ConcurrentHashMap<String, State> m = new ConcurrentHashMap<>();

    @Override
    public State begin(String key) {
        State prev = m.putIfAbsent(key, State.IN_PROGRESS);
        return prev == null ? State.STARTED : prev;
    }

    @Override
    public void done(String key) {
        m.put(key, State.DONE);
    }

    @Override
    public void release(String key) {
        m.remove(key);
    }
}

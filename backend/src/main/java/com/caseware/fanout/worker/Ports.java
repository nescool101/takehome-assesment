package com.caseware.fanout.worker;

import java.time.Duration;
import java.util.List;

/** Contracts the worker core depends on. Adapters (SQS, DynamoDB, real downstream) live outside the core. */
public final class Ports {
    private Ports() {}

    /** @param attempt 1-based count of real processing attempts so far (deferrals do not count). */
    public record Received(Job job, int attempt, Object handle) {}

    public interface JobSource {
        List<Received> poll(int max);

        void ack(Received r);

        /** Put back without consuming an attempt (capacity/breaker/duplicate-in-flight). */
        void defer(Received r, Duration delay);

        /** Put back after a real failure; the next delivery has attempt + 1. */
        void retry(Received r, Duration delay);

        void deadLetter(Received r, String reason);
    }

    /** The ~1 min call owned by another team. */
    public interface DownstreamClient {
        void load(Job job) throws DownstreamException;
    }

    public static final class DownstreamException extends Exception {
        public final boolean retryable;

        public DownstreamException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }

    /** Production impl = DynamoDB conditional put. begin() must be atomic. */
    public interface IdempotencyStore {
        enum State { STARTED, IN_PROGRESS, DONE }

        State begin(String key);

        void done(String key);

        /** Failure path: make the key startable again. */
        void release(String key);
    }

    public interface OfferCatalog {
        boolean isWithdrawn(String templateId, String version);
    }
}

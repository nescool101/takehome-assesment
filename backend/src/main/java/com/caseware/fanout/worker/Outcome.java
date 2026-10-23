package com.caseware.fanout.worker;

/** What happened to one received message. */
public sealed interface Outcome {
    record Done() implements Outcome {}
    record Skipped(String reason) implements Outcome {}
    /** Not processed now and no attempt consumed (no capacity, breaker open, duplicate in flight). */
    record Deferred(String reason) implements Outcome {}
    record Retried(int attempt) implements Outcome {}
    record DeadLettered(String reason) implements Outcome {}
}

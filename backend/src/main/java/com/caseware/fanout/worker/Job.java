package com.caseware.fanout.worker;

/** One unit of fan-out work: tell the downstream about (engagement, template version). */
public record Job(String engagementId, String firmId, String templateId, String version) {
    /** Idempotency key: same engagement + same target version = same effect, whatever the delivery count. */
    public String key() {
        return engagementId + "|" + templateId + "|" + version;
    }
}

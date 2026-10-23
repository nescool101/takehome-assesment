package com.caseware.fanout.aws;

import com.caseware.fanout.worker.Ports.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Table: hash key "k" (S). begin() is one conditional put, so only one worker wins a key. */
public class DynamoIdempotency implements IdempotencyStore {
    private final DynamoDbClient ddb;
    public final String table;
    private final Duration lease;

    /** @param lease how long an IN_PROGRESS claim blocks others; after it a crashed worker's key is retaken. */
    public DynamoIdempotency(DynamoDbClient ddb, String table, Duration lease) {
        this.ddb = ddb;
        this.table = table;
        this.lease = lease;
    }

    @Override
    public State begin(String key) {
        long now = Instant.now().getEpochSecond();
        try {
            ddb.putItem(b -> b.tableName(table)
                    .item(Map.of("k", s(key), "state", s("IN_PROGRESS"),
                            "exp", n(now + lease.toSeconds())))
                    .conditionExpression("attribute_not_exists(k) OR (#s = :ip AND exp < :now)")
                    .expressionAttributeNames(Map.of("#s", "state"))
                    .expressionAttributeValues(Map.of(":ip", s("IN_PROGRESS"), ":now", n(now))));
            return State.STARTED;
        } catch (ConditionalCheckFailedException e) {
            var item = ddb.getItem(b -> b.tableName(table).key(Map.of("k", s(key))).consistentRead(true)).item();
            // released between put and get -> treat as in progress; worker defers and retries
            return !item.isEmpty() && "DONE".equals(item.get("state").s()) ? State.DONE : State.IN_PROGRESS;
        }
    }

    public boolean isDone(String key) {
        var item = ddb.getItem(b -> b.tableName(table).key(Map.of("k", s(key))).consistentRead(true)).item();
        return !item.isEmpty() && "DONE".equals(item.get("state").s());
    }

    /** How many of these keys are DONE: BatchGetItem, 100 keys per call (status endpoint over thousands of jobs). */
    public long countDone(java.util.List<String> keys) {
        long done = 0;
        for (int i = 0; i < keys.size(); i += 100) {
            var pending = new java.util.ArrayList<Map<String, AttributeValue>>();
            for (String k : keys.subList(i, Math.min(i + 100, keys.size()))) pending.add(Map.of("k", s(k)));
            while (!pending.isEmpty()) {
                var r = ddb.batchGetItem(b -> b.requestItems(Map.of(table,
                        KeysAndAttributes.builder().keys(pending).consistentRead(true).build())));
                for (var item : r.responses().getOrDefault(table, java.util.List.of()))
                    if ("DONE".equals(item.get("state").s())) done++;
                var un = r.unprocessedKeys().get(table);
                pending.clear();
                if (un != null) pending.addAll(un.keys());
            }
        }
        return done;
    }

    @Override
    public void done(String key) {
        ddb.putItem(b -> b.tableName(table).item(Map.of("k", s(key), "state", s("DONE"))));
    }

    @Override
    public void release(String key) {
        ddb.deleteItem(b -> b.tableName(table).key(Map.of("k", s(key))));
    }

    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static AttributeValue n(long v) { return AttributeValue.builder().n(String.valueOf(v)).build(); }
}

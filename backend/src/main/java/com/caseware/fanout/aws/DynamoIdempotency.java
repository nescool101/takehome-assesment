package com.caseware.fanout.aws;

import com.caseware.fanout.worker.Ports.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Table: hash key "k" (S). begin() is one conditional put, so only one worker wins a key. */
public class DynamoIdempotency implements IdempotencyStore {
    private final DynamoDbClient ddb;
    public final String table;
    private final Duration lease;
    private static final long DONE_TTL_SECONDS = Duration.ofDays(30).toSeconds(); // enable DynamoDB TTL on "exp"
    private final String owner = UUID.randomUUID().toString(); // claim owner: a stalled worker must not release another's claim

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
                    .item(Map.of("k", s(key), "state", s("IN_PROGRESS"), "owner", s(owner),
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

    public long countDone(java.util.List<String> keys) {
        return doneKeys(keys).size();
    }

    /** Which of these keys are DONE: BatchGetItem, 100 keys per call (a whole engagement or offer in a few calls). */
    public java.util.Set<String> doneKeys(java.util.List<String> keys) {
        var done = new java.util.HashSet<String>();
        for (int i = 0; i < keys.size(); i += 100) {
            var pending = new java.util.ArrayList<Map<String, AttributeValue>>();
            for (String k : keys.subList(i, Math.min(i + 100, keys.size()))) pending.add(Map.of("k", s(k)));
            while (!pending.isEmpty()) {
                var r = ddb.batchGetItem(b -> b.requestItems(Map.of(table,
                        KeysAndAttributes.builder().keys(pending).consistentRead(true).build())));
                for (var item : r.responses().getOrDefault(table, java.util.List.of()))
                    if ("DONE".equals(item.get("state").s())) done.add(item.get("k").s());
                var un = r.unprocessedKeys().get(table);
                pending.clear();
                if (un != null) pending.addAll(un.keys());
            }
        }
        return done;
    }

    /** DONE + per-offer counter in one transaction: the counter moves exactly once per key, even on redelivery. */
    @Override
    public void done(String key) {
        String[] p = key.split("\\|", 3); // engagementId|templateId|version
        try {
            ddb.transactWriteItems(b -> b.transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(table)
                            .item(Map.of("k", s(key), "state", s("DONE"), "exp", n(Instant.now().getEpochSecond() + DONE_TTL_SECONDS)))
                            .conditionExpression("attribute_not_exists(k) OR #s <> :done")
                            .expressionAttributeNames(Map.of("#s", "state"))
                            .expressionAttributeValues(Map.of(":done", s("DONE"))).build()).build(),
                    TransactWriteItem.builder().update(Update.builder().tableName(table).key(Map.of("k", s(counterKey(p[1] + "@" + p[2]))))
                            .updateExpression("ADD n :one").expressionAttributeValues(Map.of(":one", n(1))).build()).build()));
        } catch (TransactionCanceledException e) {
            // already DONE: counted before
        }
    }

    /** Jobs DONE for an offer ("templateId@version"): one item read. Jobs finished before the counter existed are not counted. */
    public long offerDone(String offer) {
        var item = ddb.getItem(b -> b.tableName(table).key(Map.of("k", s(counterKey(offer)))).consistentRead(true)).item();
        return item.isEmpty() ? 0 : Long.parseLong(item.get("n").n());
    }

    private static String counterKey(String offer) {
        return "cnt#" + offer; // ids allow neither '#' nor '|', so it never collides with a job key
    }

    @Override
    public void release(String key) {
        try {
            ddb.deleteItem(b -> b.tableName(table).key(Map.of("k", s(key)))
                    .conditionExpression("#s = :ip AND #o = :me")
                    .expressionAttributeNames(Map.of("#s", "state", "#o", "owner"))
                    .expressionAttributeValues(Map.of(":ip", s("IN_PROGRESS"), ":me", s(owner))));
        } catch (ConditionalCheckFailedException e) {
            // claim already DONE or taken over by another worker: not ours to delete
        }
    }

    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static AttributeValue n(long v) { return AttributeValue.builder().n(String.valueOf(v)).build(); }
}

package com.caseware.fanout.aws;

import com.caseware.fanout.worker.BreakerStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Counters in one item (k = "breaker") in the idempotency table; job keys contain '|' so they never collide with it.
 * The half-open probe is a ShedLock lock held for the whole cooldown, so exactly one worker of the region probes.
 */
public class DynamoBreakerStore implements BreakerStore {
    private static final Map<String, AttributeValue> KEY = Map.of("k", s("breaker"));
    private final DynamoDbClient ddb;
    private final String table;
    private final LockProvider locks;

    public DynamoBreakerStore(DynamoDbClient ddb, String table, LockProvider locks) {
        this.ddb = ddb;
        this.table = table;
        this.locks = locks;
    }

    @Override
    public State read() {
        var item = ddb.getItem(b -> b.tableName(table).key(KEY)).item(); // eventually consistent: cheap, cached anyway
        return new State(item.containsKey("failures") ? Integer.parseInt(item.get("failures").n()) : 0,
                item.containsKey("openUntil") ? Long.parseLong(item.get("openUntil").n()) : 0);
    }

    @Override
    public int addFailure() {
        var r = ddb.updateItem(b -> b.tableName(table).key(KEY).updateExpression("ADD failures :one")
                .expressionAttributeValues(Map.of(":one", n(1))).returnValues(ReturnValue.UPDATED_NEW));
        return Integer.parseInt(r.attributes().get("failures").n());
    }

    @Override
    public void open(long untilMs) {
        conditional("SET openUntil = :v", "attribute_not_exists(openUntil) OR openUntil < :v", untilMs);
    }

    @Override
    public void reset() {
        ddb.updateItem(b -> b.tableName(table).key(KEY).updateExpression("SET failures = :z")
                .expressionAttributeValues(Map.of(":z", n(0))));
    }

    @Override
    public boolean claimProbe(long nowMs, long nextMs) {
        var d = Duration.ofMillis(nextMs - nowMs); // never unlocked: held until the cooldown is over
        return locks.lock(new LockConfiguration(Instant.ofEpochMilli(nowMs), "breaker-probe", d, d)).isPresent();
    }

    private boolean conditional(String update, String condition, long v) {
        var vals = Map.of(":v", n(v));
        try {
            ddb.updateItem(b -> b.tableName(table).key(KEY).updateExpression(update).conditionExpression(condition)
                    .expressionAttributeValues(vals));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static AttributeValue n(long v) { return AttributeValue.builder().n(String.valueOf(v)).build(); }
}

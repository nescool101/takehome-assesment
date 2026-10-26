package com.caseware.fanout.aws;

import com.caseware.fanout.worker.Ports.OfferCatalog;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Offers table (hash key offerId = "templateId|version"); item with withdrawn=true means withdrawn.
 * Optional in-process TTL cache (ttl zero = off): offers change rarely and every job asks, so it saves a DynamoDB read per job.
 * Cost of caching: a withdrawal reaches this process up to ttl late (a stale job does one harmless extra load).
 * ponytail: unbounded map, but keys are offers (about 173 a month); swap for Caffeine if that stops being true.
 */
public class DynamoOfferCatalog implements OfferCatalog {
    private record Entry(boolean withdrawn, long expiresNanos) {}

    private final DynamoDbClient ddb;
    public final String table;
    private final long ttlNanos;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** Real DynamoDB reads, so load runs can compare cache on vs off. */
    public final AtomicLong reads = new AtomicLong();

    public DynamoOfferCatalog(DynamoDbClient ddb, String table, Duration cacheTtl) {
        this.ddb = ddb;
        this.table = table;
        this.ttlNanos = cacheTtl.toNanos();
    }

    public DynamoOfferCatalog(DynamoDbClient ddb, String table) {
        this(ddb, table, Duration.ZERO);
    }

    @Override
    public boolean isWithdrawn(String templateId, String version) {
        String id = templateId + "|" + version;
        long now = System.nanoTime();
        if (ttlNanos > 0) {
            Entry e = cache.get(id);
            if (e != null && now < e.expiresNanos) return e.withdrawn;
        }
        reads.incrementAndGet();
        var item = ddb.getItem(b -> b.tableName(table).consistentRead(true)
                .key(Map.of("offerId", AttributeValue.builder().s(id).build()))).item();
        var w = item.get("withdrawn");
        boolean withdrawn = w != null && Boolean.TRUE.equals(w.bool());
        // withdrawal is irreversible: never let a racing stale read overwrite a withdrawn entry
        if (ttlNanos > 0) cache.merge(id, new Entry(withdrawn, now + ttlNanos), (old, nu) -> old.withdrawn ? old : nu);
        return withdrawn;
    }

    public void withdraw(String templateId, String version) {
        String id = templateId + "|" + version;
        ddb.putItem(b -> b.tableName(table).item(Map.of(
                "offerId", AttributeValue.builder().s(id).build(),
                "withdrawn", AttributeValue.builder().bool(true).build())));
        cache.put(id, new Entry(true, Long.MAX_VALUE)); // this process sees it at once; other processes within ttl
    }
}

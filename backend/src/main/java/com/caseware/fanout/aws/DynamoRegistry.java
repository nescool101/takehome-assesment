package com.caseware.fanout.aws;

import com.caseware.fanout.worker.Job;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Table "<region>-registry": hash engagementId, range offer ("templateId@version"), GSI "offer-index" on offer.
 * The published-jobs index: survives restarts, so badges and offer status do not depend on process memory.
 */
public class DynamoRegistry {
    private final DynamoDbClient ddb;
    private final String table;

    public DynamoRegistry(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    /** Idempotent: a repeated publish rewrites the same (engagement, offer) items. */
    public void putAll(List<Job> jobs) {
        for (int i = 0; i < jobs.size(); i += 25) {
            var reqs = new ArrayList<WriteRequest>();
            for (Job j : jobs.subList(i, Math.min(i + 25, jobs.size())))
                reqs.add(WriteRequest.builder().putRequest(p -> p.item(Map.of(
                        "engagementId", s(j.engagementId()), "offer", s(j.templateId() + "@" + j.version()),
                        "firmId", s(j.firmId()), "templateId", s(j.templateId()), "version", s(j.version())))).build());
            Map<String, List<WriteRequest>> todo = Map.of(table, reqs);
            for (int tries = 0; !todo.isEmpty(); tries++) {
                if (tries == 8) throw new IllegalStateException("registry write throttled");
                var cur = todo;
                var r = ddb.batchWriteItem(b -> b.requestItems(cur));
                todo = r.unprocessedItems();
            }
        }
    }

    public List<Job> byEngagement(String engagementId) {
        return query(b -> b.keyConditionExpression("engagementId = :v").consistentRead(true)
                .expressionAttributeValues(Map.of(":v", s(engagementId))));
    }

    /** GSI read: eventually consistent (a job published a moment ago may not show yet). */
    public List<Job> byOffer(String offer) {
        return query(b -> b.indexName("offer-index").keyConditionExpression("#o = :v")
                .expressionAttributeNames(Map.of("#o", "offer")).expressionAttributeValues(Map.of(":v", s(offer))));
    }

    /** ponytail: full-table COUNT scan, ops metric only; keep a counter item if /metrics gets polled hard. */
    public long count() {
        long n = 0;
        for (var page : ddb.scanPaginator(b -> b.tableName(table).select(Select.COUNT))) n += page.count();
        return n;
    }

    private List<Job> query(java.util.function.Consumer<QueryRequest.Builder> c) {
        List<Job> out = new ArrayList<>();
        for (var page : ddb.queryPaginator(b -> {
            b.tableName(table);
            c.accept(b);
        }))
            for (var i : page.items())
                out.add(new Job(i.get("engagementId").s(), i.get("firmId").s(), i.get("templateId").s(), i.get("version").s()));
        return out;
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }
}

package com.caseware.fanout.aws;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Table "<region>-decisions": hash engagementId, range decidedAt. One item per decision, with the job keys it covered. */
public class DynamoDecisions {
    private final DynamoDbClient ddb;
    public final String table;

    public DynamoDecisions(DynamoDbClient ddb, String table) {
        this.ddb = ddb;
        this.table = table;
    }

    public void record(String engagementId, String action, List<String> jobKeys) {
        if (jobKeys.isEmpty()) return; // a string set cannot be empty, and there is nothing to record
        ddb.putItem(b -> b.tableName(table).item(Map.of(
                "engagementId", AttributeValue.builder().s(engagementId).build(),
                "decidedAt", AttributeValue.builder().s(Instant.now().toString()).build(),
                "action", AttributeValue.builder().s(action).build(),
                "offers", AttributeValue.builder().ss(jobKeys).build())));
    }

    /** Job keys the user already decided on for this engagement. */
    public Set<String> decidedKeys(String engagementId) {
        Set<String> out = new HashSet<>();
        for (var page : ddb.queryPaginator(b -> b.tableName(table).consistentRead(true)
                .keyConditionExpression("engagementId = :e")
                .expressionAttributeValues(Map.of(":e", AttributeValue.builder().s(engagementId).build()))))
            for (var item : page.items()) out.addAll(item.get("offers").ss());
        return out;
    }
}

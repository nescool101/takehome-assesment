package com.caseware.fanout.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.caseware.fanout.aws.*;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Integration layer: real SQS + DynamoDB semantics on LocalStack (docker compose up localstack). Skips if down. */
class LocalStackTest {
    static final URI EP = URI.create("http://localhost:4566");
    static SqsClient sqs;
    static DynamoDbClient ddb;

    @BeforeAll
    static void connect() {
        try (var s = new Socket("localhost", 4566)) {
        } catch (Exception e) {
            assumeTrue(false, "LocalStack not running");
        }
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        sqs = SqsClient.builder().endpointOverride(EP).region(Region.US_EAST_1).credentialsProvider(creds).build();
        ddb = DynamoDbClient.builder().endpointOverride(EP).region(Region.US_EAST_1).credentialsProvider(creds).build();
    }

    private static void table(String name, String hash) {
        ddb.createTable(b -> b.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName(hash).keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName(hash)
                        .attributeType(ScalarAttributeType.S).build()));
    }

    private static boolean empty(String url) {
        var a = sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)).attributes();
        return a.values().stream().allMatch("0"::equals);
    }

    private record Env(SqsJobSource src, String q, String dlq, DynamoIdempotency idem, DynamoOfferCatalog cat) {}

    private static Env env() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String dlq = sqs.createQueue(b -> b.queueName("dlq-" + id)).queueUrl();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(dlq).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String q = sqs.createQueue(b -> b.queueName("q-" + id).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "30",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"20\"}"))).queueUrl();
        table("idem-" + id, "k");
        table("offers-" + id, "offerId");
        return new Env(new SqsJobSource(sqs, q, dlq), q, dlq,
                new DynamoIdempotency(ddb, "idem-" + id, Duration.ofMinutes(10)),
                new DynamoOfferCatalog(ddb, "offers-" + id));
    }

    private static FanOutWorker worker(Env e, SimDownstream d, int maxAttempts) {
        return new FanOutWorker(e.src, d, e.idem, e.cat, new FirmLimiter(5),
                new CircuitBreaker(1_000, 1, System::currentTimeMillis),
                new Backoff(Duration.ofMillis(5), Duration.ofMillis(50), new Random(1)),
                10, maxAttempts, Duration.ofSeconds(1));
    }

    private static void drain(FanOutWorker w, Env e) {
        long deadline = System.currentTimeMillis() + 60_000;
        w.run(() -> empty(e.q) || System.currentTimeMillis() > deadline, 10, Duration.ofMillis(50));
        assertTrue(empty(e.q), "queue not drained within 60s");
    }

    @Test
    void duplicatesRunOnceAndWithdrawnIsSkipped() {
        Env e = env();
        ddb.putItem(b -> b.tableName(e.cat.table).item(Map.of(
                "offerId", AttributeValue.builder().s("tpl|v-bad").build(),
                "withdrawn", AttributeValue.builder().bool(true).build())));
        SimDownstream d = new SimDownstream(10);
        for (int i = 0; i < 20; i++) e.src.send(new Job("e" + (i % 10), "f" + (i % 3), "tpl", "v7")); // each twice
        e.src.send(new Job("x", "f1", "tpl", "v-bad"));
        drain(worker(e, d, 3), e);
        assertEquals(10, d.calls.get());
        assertEquals(10, e.idem.countDone(new java.util.ArrayList<>(d.perKey.keySet())), "batch status read");
        assertTrue(d.perKey.values().stream().allMatch(c -> c.get() == 1));
        assertFalse(d.perKey.containsKey("x|tpl|v-bad"));
        assertTrue(empty(e.dlq));
    }

    @Test
    void transientRetriesThenSucceeds_fatalGoesToDlq() {
        Env e = env();
        var fails = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        SimDownstream d = new SimDownstream(5, j -> {
            if (j.engagementId().equals("bad")) return new Ports.DownstreamException("boom", false);
            return fails.merge(j.key(), 1, Integer::sum) <= 2 ? new Ports.DownstreamException("flaky", true) : null;
        });
        e.src.send(new Job("flaky", "f1", "tpl", "v7"));
        e.src.send(new Job("bad", "f1", "tpl", "v7"));
        drain(worker(e, d, 5), e);
        assertEquals(3, d.perKey.get("flaky|tpl|v7").get(), "2 failures + 1 success");
        assertEquals(1, d.perKey.get("bad|tpl|v7").get(), "fatal not retried");
        var dead = sqs.receiveMessage(b -> b.queueUrl(e.dlq).waitTimeSeconds(2).maxNumberOfMessages(10)
                .messageAttributeNames("All")).messages();
        assertEquals(1, dead.size());
        assertEquals("bad|f1|tpl|v7", dead.get(0).body());
        // flaky key finished, so redelivery is a no-op
        assertEquals(Ports.IdempotencyStore.State.DONE, e.idem.begin("flaky|tpl|v7"));
    }

    @Test
    void offerCacheSavesReadsAndWithdrawIsSeenAtOnce() {
        Env e = env();
        var cached = new DynamoOfferCatalog(ddb, e.cat.table, Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) assertFalse(cached.isWithdrawn("tpl", "v9"));
        assertEquals(1, cached.reads.get(), "4 of 5 calls served from cache");
        cached.withdraw("tpl", "v9");
        assertTrue(cached.isWithdrawn("tpl", "v9"), "withdraw invalidates this process's cache");
        for (int i = 0; i < 5; i++) assertFalse(e.cat.isWithdrawn("tpl", "v-other")); // no cache: every call reads
        assertEquals(5, e.cat.reads.get());
    }

    @Test
    void staleLeaseIsRetakenLiveLeaseIsNot() {
        Env e = env();
        assertEquals(Ports.IdempotencyStore.State.STARTED, e.idem.begin("k1"));
        assertEquals(Ports.IdempotencyStore.State.IN_PROGRESS, e.idem.begin("k1"));
        var zero = new DynamoIdempotency(ddb, e.idem.table, Duration.ZERO);
        assertEquals(Ports.IdempotencyStore.State.STARTED, zero.begin("k2"));
        try { Thread.sleep(1_100); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
        assertEquals(Ports.IdempotencyStore.State.STARTED, zero.begin("k2"), "expired lease retaken");
    }
}

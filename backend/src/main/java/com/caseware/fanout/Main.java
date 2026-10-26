package com.caseware.fanout;

import com.caseware.fanout.api.*;
import com.caseware.fanout.aws.*;
import com.caseware.fanout.config.AppConfig;
import com.caseware.fanout.worker.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** One region's worker + ops API. Queues/tables come from Terraform ("<region-short>-fanout" etc). */
public final class Main {
    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.fromEnv();
        String ep = env("AWS_ENDPOINT_URL", "http://localhost:4566");
        String name = env("REGION_NAME", "us");
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                env("AWS_ACCESS_KEY_ID", "test"), env("AWS_SECRET_ACCESS_KEY", "test")));
        Region region = Region.of(env("AWS_REGION", "us-east-1"));
        var sqs = SqsClient.builder().endpointOverride(URI.create(ep)).region(region).credentialsProvider(creds).build();
        var ddb = DynamoDbClient.builder().endpointOverride(URI.create(ep)).region(region).credentialsProvider(creds).build();

        String q = sqs.getQueueUrl(b -> b.queueName(name + "-fanout")).queueUrl();
        String dlq = sqs.getQueueUrl(b -> b.queueName(name + "-fanout-dlq")).queueUrl();
        // the knob must really drive the queue; the idempotency lease stays 2x it so a live worker is never retaken
        sqs.setQueueAttributes(b -> b.queueUrl(q).attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,
                String.valueOf(cfg.visibilityTimeoutSeconds()))));
        var source = new SqsJobSource(sqs, q, dlq);
        var idem = new DynamoIdempotency(ddb, name + "-idempotency", Duration.ofSeconds(2L * cfg.visibilityTimeoutSeconds()));
        var decisions = new DynamoDecisions(ddb, name + "-decisions");
        var catalog = new DynamoOfferCatalog(ddb, name + "-offers",
                cfg.cacheEnabled() ? Duration.ofSeconds(cfg.cacheTtlSeconds()) : Duration.ZERO);

        int permits = cfg.profile().downstreamPermits();
        var downstream = new SimulatedDownstream(cfg.simLatencyMs());
        var worker = new FanOutWorker(source, downstream, idem, catalog,
                new FirmLimiter(Math.max(1, permits / 2)), // ponytail: fixed half-of-permits per-firm cap, tune from load results
                new CircuitBreaker(cfg.breakerFailures(), cfg.breakerCooldownMs(), System::currentTimeMillis),
                new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30), new Random()),
                permits, cfg.retryMaxAttempts(), Duration.ofMillis(cfg.deferDelayMs()));

        new OpsApi(Integer.parseInt(env("PORT", "8080")), source, idem, catalog, decisions, sqs, q, dlq,
                downstream::statsJson, configJson(cfg)).start();
        System.out.println("ops api up, permits=" + permits + " simLatencyMs=" + cfg.simLatencyMs());
        worker.run(() -> false, 10, Duration.ofMillis(100));
    }

    private static String configJson(AppConfig c) {
        var p = c.profile();
        return "{\"scenario\":\"" + c.scenario() + "\",\"budget\":\"" + c.budget() + "\",\"permits\":" + p.downstreamPermits()
                + ",\"simLatencyMs\":" + c.simLatencyMs() + ",\"loadSeconds\":" + p.engagementLoadTime().toSeconds()
                + ",\"activeEngagements\":" + p.activeEngagements() + ",\"firms\":" + p.firms()
                + ",\"medianPerFirm\":" + p.medianEngagementsPerFirm() + ",\"maxPerFirm\":" + p.maxEngagementsPerFirm() + ",\"cacheEnabled\":" + c.cacheEnabled()
                + ",\"tier\":\"" + c.tier() + "\",\"budgetCapUsd\":" + c.budgetCapUsd()
                + ",\"visibilityTimeoutSeconds\":" + c.visibilityTimeoutSeconds() + "}";
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}

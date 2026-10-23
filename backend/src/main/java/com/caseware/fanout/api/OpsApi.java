package com.caseware.fanout.api;

import com.caseware.fanout.aws.*;
import com.caseware.fanout.worker.Job;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Ops API on the JDK HttpServer. Offer id = "templateId@version".
 * ponytail: published jobs and decisions are in memory (lost on restart); the real home is the registry/decisions tables.
 */
public final class OpsApi {
    private final SqsJobSource source;
    private final DynamoIdempotency idem;
    private final DynamoOfferCatalog catalog;
    private final SqsClient sqs;
    private final String queueUrl, dlqUrl;
    private final Map<String, List<Job>> byOffer = new ConcurrentHashMap<>();
    private final Map<String, List<Job>> byEngagement = new ConcurrentHashMap<>();
    private final Set<String> decided = ConcurrentHashMap.newKeySet(); // job keys applied/declined
    private final java.util.function.Supplier<String> downstreamStats;
    private final String configJson;
    private final HttpServer server;

    public OpsApi(int port, SqsJobSource source, DynamoIdempotency idem, DynamoOfferCatalog catalog,
                  SqsClient sqs, String queueUrl, String dlqUrl,
                  java.util.function.Supplier<String> downstreamStats, String configJson) throws IOException {
        this.downstreamStats = downstreamStats;
        this.configJson = configJson;
        this.source = source;
        this.idem = idem;
        this.catalog = catalog;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool()); // platform threads: virtual ones share carriers with the worker and starve behind it
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }

    private static final Pattern OFFER = Pattern.compile("/admin/offers/([^/]+)/(status|withdraw)");
    private static final Pattern ENG = Pattern.compile("/engagements/([^/]+)/(status|decision)");

    private void handle(HttpExchange x) throws IOException {
        x.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type");
        x.getResponseHeaders().add("Content-Type", "application/json");
        try {
            String m = x.getRequestMethod(), path = x.getRequestURI().getPath();
            Matcher o = OFFER.matcher(path), e = ENG.matcher(path);
            if (m.equals("OPTIONS")) send(x, 204, "");
            else if (m.equals("GET") && path.equals("/health")) send(x, 200, "{\"ok\":true}");
            else if (m.equals("GET") && path.equals("/metrics")) send(x, 200, metrics());
            else if (m.equals("POST") && path.equals("/admin/publishes")) publish(x);
            else if (o.matches() && m.equals("GET") && o.group(2).equals("status")) send(x, 200, offerStatus(o.group(1)));
            else if (o.matches() && m.equals("POST") && o.group(2).equals("withdraw")) withdraw(x, o.group(1));
            else if (e.matches() && m.equals("GET") && e.group(2).equals("status")) send(x, 200, engagementStatus(e.group(1)));
            else if (e.matches() && m.equals("POST") && e.group(2).equals("decision")) decision(x, e.group(1));
            else send(x, 404, err("not found"));
        } catch (IllegalArgumentException ex) {
            send(x, 400, err(ex.getMessage()));
        } catch (RuntimeException ex) {
            send(x, 500, err(String.valueOf(ex.getMessage())));
        }
    }

    /** Body: {"templateId":"tpl","version":"v7","engagements":[{"id":"e1","firmId":"f1"},...]} (flat, parsed by regex). */
    private void publish(HttpExchange x) throws IOException {
        String b = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String tpl = field(b, "templateId"), ver = field(b, "version");
        Matcher em = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"firmId\"\\s*:\\s*\"([^\"]+)\"\\s*\\}").matcher(b);
        List<Job> batch = new ArrayList<>();
        String offer = tpl + "@" + ver;
        while (em.find()) {
            Job j = new Job(em.group(1), em.group(2), tpl, ver);
            byOffer.computeIfAbsent(offer, k -> Collections.synchronizedList(new ArrayList<>())).add(j);
            byEngagement.computeIfAbsent(j.engagementId(), k -> Collections.synchronizedList(new ArrayList<>())).add(j);
            batch.add(j);
        }
        int n = batch.size();
        source.sendAll(batch);
        if (n == 0) throw new IllegalArgumentException("no engagements");
        send(x, 202, "{\"offerId\":\"" + offer + "\",\"enqueued\":" + n + "}");
    }

    private String offerStatus(String offer) {
        List<Job> jobs = snapshot(byOffer.getOrDefault(offer, List.of()));
        long done = idem.countDone(jobs.stream().map(Job::key).toList());
        boolean w = !jobs.isEmpty() && catalog.isWithdrawn(jobs.get(0).templateId(), jobs.get(0).version());
        return "{\"offerId\":\"" + offer + "\",\"published\":" + jobs.size() + ",\"done\":" + done
                + ",\"inFlightOrQueued\":" + (jobs.size() - done) + ",\"withdrawn\":" + w + "}";
    }

    /** Copy under the lock so no lock is held across a DynamoDB call (a slow call used to block every other status request). */
    private static List<Job> snapshot(List<Job> l) {
        synchronized (l) { return List.copyOf(l); }
    }

    private void withdraw(HttpExchange x, String offer) throws IOException {
        String[] p = offer.split("@", 2);
        if (p.length != 2) throw new IllegalArgumentException("offer id must be templateId@version");
        catalog.withdraw(p[0], p[1]);
        send(x, 200, offerStatus(offer));
    }

    /** Badge = a fanned-out, not-withdrawn, undecided offer exists. Summary is deterministic text (no LLM). */
    private String engagementStatus(String eng) {
        List<String> pending = new ArrayList<>();
        for (Job j : snapshot(byEngagement.getOrDefault(eng, List.of())))
            if (!decided.contains(j.key()) && idem.isDone(j.key()) && !catalog.isWithdrawn(j.templateId(), j.version()))
                pending.add(j.templateId() + "@" + j.version());
        String summary = pending.isEmpty() ? "" : "Template update available: " + String.join(", ", pending);
        return "{\"engagementId\":\"" + eng + "\",\"pending\":" + !pending.isEmpty()
                + ",\"offers\":" + json(pending) + ",\"summary\":\"" + summary + "\"}";
    }

    private void decision(HttpExchange x, String eng) throws IOException {
        String b = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String action = field(b, "action");
        if (!action.equals("apply") && !action.equals("decline")) throw new IllegalArgumentException("action: apply|decline");
        for (Job j : byEngagement.getOrDefault(eng, List.of())) decided.add(j.key());
        send(x, 200, engagementStatus(eng));
    }

    private String metrics() {
        return "{\"queue\":" + depth(queueUrl) + ",\"dlq\":" + depth(dlqUrl) + ",\"published\":"
                + byOffer.values().stream().mapToInt(List::size).sum()
                + ",\"catalogReads\":" + catalog.reads.get() + ",\"downstream\":" + downstreamStats.get() + ",\"config\":" + configJson + "}";
    }

    private String depth(String url) {
        var a = sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return "{\"visible\":" + a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                + ",\"inFlight\":" + a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE) + "}";
    }

    private static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find() || m.group(1).isBlank()) throw new IllegalArgumentException("missing " + name);
        return m.group(1);
    }

    private static String json(List<String> l) {
        return "[" + String.join(",", l.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    private static String err(String m) { return "{\"error\":\"" + m.replace("\"", "'") + "\"}"; }

    private static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, code == 204 ? -1 : b.length);
        if (code != 204) x.getResponseBody().write(b);
        x.close();
    }
}

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Ops API on the JDK HttpServer. Offer id = "templateId@version".
 * Published jobs live in the registry table, decisions in the decisions table: nothing is held in process memory.
 */
public final class OpsApi {
    private final SqsJobSource source;
    private final DynamoIdempotency idem;
    private final DynamoOfferCatalog catalog;
    private final DynamoDecisions decisions;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SqsClient sqs;
    private final String queueUrl, dlqUrl;
    private final DynamoRegistry registry;
    private final java.util.function.Supplier<String> downstreamStats;
    private final String configJson;
    private final HttpServer server;

    public OpsApi(int port, SqsJobSource source, DynamoIdempotency idem, DynamoOfferCatalog catalog,
                  DynamoDecisions decisions, DynamoRegistry registry, SqsClient sqs, String queueUrl, String dlqUrl,
                  java.util.function.Supplier<String> downstreamStats, String configJson) throws IOException {
        this.downstreamStats = downstreamStats;
        this.configJson = configJson;
        this.source = source;
        this.idem = idem;
        this.catalog = catalog;
        this.decisions = decisions;
        this.registry = registry;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(32)); // bounded; platform threads: virtual ones share carriers with the worker and starve behind it
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }

    private static final Pattern OFFER = Pattern.compile("/admin/offers/([^/]+)/(status|withdraw)");
    private static final Pattern ENG = Pattern.compile("/engagements/([^/]+)/(status|decision)");

    private void handle(HttpExchange x) throws IOException {
        x.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type,x-admin-token");
        x.getResponseHeaders().add("Content-Type", "application/json");
        try {
            String m = x.getRequestMethod(), path = x.getRequestURI().getPath();
            Matcher o = OFFER.matcher(path), e = ENG.matcher(path);
            if (m.equals("OPTIONS")) send(x, 204, "");
            else if (!authorized(x, m, path)) send(x, 403, err("forbidden"));
            else if (m.equals("GET") && path.equals("/health")) send(x, 200, obj("ok", true));
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
            System.err.println("500 " + x.getRequestURI().getPath() + ": " + ex);
            send(x, 500, err("internal error"));
        }
    }

    /**
     * Mutating calls must be application/json: a cross-site page can only send text/plain without a preflight.
     * ponytail: ADMIN_TOKEN is a shared secret on /admin/*; swap for real auth (the gateway API key does not cover :8080).
     */
    private static boolean authorized(HttpExchange x, String method, String path) {
        if (method.equals("POST")) {
            String ct = x.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.toLowerCase().startsWith("application/json")) return false;
        }
        String token = System.getenv("ADMIN_TOKEN");
        return token == null || token.isBlank() || !path.startsWith("/admin/")
                || token.equals(x.getRequestHeaders().getFirst("x-admin-token"));
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}"); // no '|' '@' quotes or backslashes: ids go into keys and JSON
    private static String id(String v, String what) {
        if (v == null || !ID.matcher(v).matches()) throw new IllegalArgumentException("bad " + what + " (allowed: letters, digits, . _ : -)");
        return v;
    }

    private static JsonNode parse(HttpExchange x) throws IOException {
        try {
            JsonNode n = JSON.readTree(x.getRequestBody());
            if (n == null || !n.isObject()) throw new IllegalArgumentException("body must be a JSON object");
            return n;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }

    private static String text(JsonNode n, String name) {
        JsonNode v = n.get(name);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException("missing " + name);
        return v.asText();
    }

    /** Body: {"templateId":"tpl","version":"v7","engagements":[{"id":"e1","firmId":"f1"},...]} (extra fields are ignored). */
    private void publish(HttpExchange x) throws IOException {
        JsonNode b = parse(x);
        String tpl = id(text(b, "templateId"), "templateId"), ver = id(text(b, "version"), "version");
        JsonNode engs = b.get("engagements");
        if (engs == null || !engs.isArray() || engs.isEmpty()) throw new IllegalArgumentException("no engagements");
        Set<Job> batch = new LinkedHashSet<>(); // a repeated engagement in the body is one job
        for (JsonNode e : engs)
            batch.add(new Job(id(text(e, "id"), "engagement id"), id(text(e, "firmId"), "firmId"), tpl, ver));
        String offer = tpl + "@" + ver;
        catalog.register(tpl, ver);
        source.sendAll(List.copyOf(batch));
        // index only after the enqueue succeeded: a failed publish leaves no phantom jobs; a failed index write -> 500,
        // the caller republishes and idempotency absorbs the duplicate messages
        registry.putAll(List.copyOf(batch));
        send(x, 202, obj("offerId", offer, "enqueued", batch.size()));
    }

    private Object offerStatus(String offer) {
        String[] p = offerParts(offer);
        long published = registry.countByOffer(offer), done = idem.offerDone(offer);
        boolean w = catalog.isWithdrawn(p[0], p[1]); // the catalog is the source of truth, also after a restart
        return obj("offerId", offer, "published", published, "done", done,
                "inFlightOrQueued", published - done, "withdrawn", w);
    }

    private static String[] offerParts(String offer) {
        String[] p = offer.split("@", 2);
        if (p.length != 2) throw new IllegalArgumentException("offer id must be templateId@version");
        id(p[0], "templateId");
        id(p[1], "version");
        return p;
    }

    private void withdraw(HttpExchange x, String offer) throws IOException {
        String[] p = offerParts(offer);
        if (!catalog.exists(p[0], p[1])) throw new IllegalArgumentException("unknown offer " + offer);
        catalog.withdraw(p[0], p[1]);
        send(x, 200, offerStatus(offer));
    }

    /** Badge = a fanned-out, not-withdrawn, undecided offer exists. Summary is deterministic text (no LLM). */
    private Object engagementStatus(String eng) {
        List<String> pending = new ArrayList<>();
        for (Job j : pendingJobs(eng)) pending.add(j.templateId() + "@" + j.version());
        String summary = pending.isEmpty() ? "" : "Template update available: " + String.join(", ", pending);
        return obj("engagementId", eng, "pending", !pending.isEmpty(), "offers", pending, "summary", summary);
    }

    private List<Job> pendingJobs(String eng) {
        id(eng, "engagement id");
        Set<String> decided = decisions.decidedKeys(eng);
        List<Job> open = registry.byEngagement(eng).stream().filter(j -> !decided.contains(j.key())).toList();
        Set<String> done = idem.doneKeys(open.stream().map(Job::key).toList());
        return open.stream().filter(j -> done.contains(j.key()) && !catalog.isWithdrawn(j.templateId(), j.version())).toList();
    }

    private void decision(HttpExchange x, String eng) throws IOException {
        String action = text(parse(x), "action");
        if (!action.equals("apply") && !action.equals("decline")) throw new IllegalArgumentException("action: apply|decline");
        // only offers the user could see: one published a moment later must keep its badge
        // ponytail: apply and decline both just clear the badge (no apply target exists yet); the action is stored for the audit trail
        decisions.record(eng, action, pendingJobs(eng).stream().map(Job::key).toList());
        send(x, 200, engagementStatus(eng));
    }

    private Object metrics() throws IOException {
        return obj("queue", depth(queueUrl), "dlq", depth(dlqUrl), "published", registry.count(),
                "catalogReads", catalog.reads.get(), "downstream", JSON.readTree(downstreamStats.get()), "config", JSON.readTree(configJson));
    }

    private Object depth(String url) {
        var a = sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return obj("visible", a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES),
                "inFlight", a.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    private static Map<String, Object> obj(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Object err(String m) {
        return obj("error", m);
    }

    private static void send(HttpExchange x, int code, Object body) throws IOException {
        send(x, code, body instanceof String s ? s : JSON.writeValueAsString(body));
    }

    private static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, code == 204 ? -1 : b.length);
        if (code != 204) x.getResponseBody().write(b);
        x.close();
    }
}

package com.caseware.fanout.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One JSON object per stdout line (CloudWatch Logs friendly); {@link #emf} lines double as CloudWatch metrics. */
public final class Log {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Set once at startup (the regional stack name); goes on every line and is the metric dimension. */
    public static volatile String service = "fanout";

    private Log() {}

    public static void event(String level, String event, Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ts", java.time.Instant.now().toString());
        m.put("level", level);
        m.put("service", service);
        m.put("event", event);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        print(m);
    }

    /** CloudWatch Embedded Metric Format: a log line the agent turns into metrics (namespace "Fanout", dimension Service). */
    public static void emf(Map<String, ? extends Number> metrics) {
        var m = new LinkedHashMap<String, Object>();
        m.put("_aws", Map.of("Timestamp", System.currentTimeMillis(), "CloudWatchMetrics", List.of(Map.of(
                "Namespace", "Fanout", "Dimensions", List.of(List.of("Service")),
                "Metrics", metrics.keySet().stream().map(k -> Map.of("Name", k, "Unit", "Count")).toList()))));
        m.put("Service", service);
        m.putAll(metrics);
        print(m);
    }

    private static void print(Map<String, Object> m) {
        try {
            System.out.println(JSON.writeValueAsString(m));
        } catch (Exception e) {
            System.out.println("{\"level\":\"error\",\"event\":\"log_failed\"}");
        }
    }
}

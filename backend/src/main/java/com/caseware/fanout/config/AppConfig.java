package com.caseware.fanout.config;

import java.time.Duration;
import java.util.Map;

/** Single source of numbers: scenario defaults, any value overridable by env var (blank = unset). */
public record AppConfig(
        Scenario scenario,
        ScaleProfile profile,
        BudgetScenario budget,
        int budgetCapUsd,
        BudgetTier tier,
        int retryMaxAttempts,
        int visibilityTimeoutSeconds,
        long simLatencyMs,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        int breakerFailures,
        long breakerCooldownMs,
        long deferDelayMs) {

    public static AppConfig fromEnv() {
        return from(System.getenv());
    }

    public static AppConfig from(Map<String, String> env) {
        Scenario scenario = Scenario.valueOf(get(env, "SCENARIO", "MID").toUpperCase());
        ScaleProfile d = scenario.profile;
        ScaleProfile p = new ScaleProfile(
                getInt(env, "FIRMS", d.firms()),
                getInt(env, "ACTIVE_ENGAGEMENTS", d.activeEngagements()),
                getInt(env, "PRODUCTS", d.products()),
                getInt(env, "MEDIAN_ENGAGEMENTS_PER_FIRM", d.medianEngagementsPerFirm()),
                getInt(env, "MAX_ENGAGEMENTS_PER_FIRM", d.maxEngagementsPerFirm()),
                Duration.ofSeconds(getInt(env, "ENGAGEMENT_LOAD_SECONDS", (int) d.engagementLoadTime().toSeconds())),
                Duration.ofDays(getInt(env, "PUBLISH_CADENCE_DAYS", (int) d.publishCadencePerProduct().toDays())),
                getInt(env, "DOWNSTREAM_PERMITS", d.downstreamPermits()),
                Double.parseDouble(get(env, "ENUMERATION_COVERAGE", String.valueOf(d.enumerationCoverage()))));
        BudgetScenario budget = BudgetScenario.valueOf(get(env, "BUDGET", "MID").toUpperCase());
        return new AppConfig(
                scenario, p, budget,
                getInt(env, "BUDGET_CAP_USD", budget.monthlyCapUsd),
                BudgetTier.valueOf(get(env, "BUDGET_TIER", "TARGET").toUpperCase()),
                getInt(env, "RETRY_MAX_ATTEMPTS", 3),
                getInt(env, "SQS_VISIBILITY_TIMEOUT_SECONDS", 300),
                getInt(env, "SIM_LATENCY_MS", 100),
                Boolean.parseBoolean(get(env, "CACHE_ENABLED", "false")),
                getInt(env, "CACHE_TTL_SECONDS", 5),
                getInt(env, "BREAKER_FAILURES", 5),
                getInt(env, "BREAKER_COOLDOWN_MS", 5_000),
                getInt(env, "DEFER_DELAY_MS", 1_000));
    }

    private static String get(Map<String, String> env, String key, String dflt) {
        String v = env.get(key);
        if (v == null) return dflt;
        v = v.replaceFirst("\\s+#.*$", "").trim(); // tolerate trailing "# comment" from .env files
        return v.isEmpty() ? dflt : v;
    }

    private static int getInt(Map<String, String> env, String key, int dflt) {
        return Integer.parseInt(get(env, key, String.valueOf(dflt)));
    }
}

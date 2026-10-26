package com.caseware.fanout.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void defaultsToMid() {
        AppConfig c = AppConfig.from(Map.of());
        assertEquals(Scenario.MID, c.scenario());
        assertEquals(100, c.profile().downstreamPermits());
        assertEquals(3_000, c.budgetCapUsd());
        assertEquals(BudgetTier.TARGET, c.tier());
    }

    @Test
    void optimisticIsTheBrief() {
        ScaleProfile p = AppConfig.from(Map.of("SCENARIO", "optimistic")).profile();
        assertEquals(4_000, p.firms());
        assertEquals(800_000, p.activeEngagements());
        assertEquals(40, p.products());
    }

    @Test
    void envOverridesAndBlanksAndComments() {
        AppConfig c = AppConfig.from(Map.of(
                "SCENARIO", "PESSIMISTIC", "DOWNSTREAM_PERMITS", "20 # tighter",
                "FIRMS", "", "BUDGET", "PESSIMISTIC", "BUDGET_CAP_USD", "1500"));
        assertEquals(20, c.profile().downstreamPermits());
        assertEquals(12_000, c.profile().firms()); // blank = scenario default
        assertEquals(1_500, c.budgetCapUsd());     // explicit override wins over BUDGET
    }

    /** Arithmetic quoted in the design doc: 1 min x engagements / permits. */
    @Test
    void backfillDurationsMatchDoc() {
        assertEquals(2.8, days(Scenario.OPTIMISTIC), 0.05);
        assertEquals(8.3, days(Scenario.MID), 0.05);
        assertEquals(33.3, days(Scenario.PESSIMISTIC), 0.05);
        assertEquals(173, Scenario.OPTIMISTIC.profile.publishesPerMonth());
    }

    private static double days(Scenario s) {
        return s.profile.backfillDuration().toMinutes() / 1440.0;
    }

    @Test
    void badScenarioFails() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("SCENARIO", "WILD")));
    }

    @Test
    void workerKnobsHaveDefaultsAndOverrides() {
        AppConfig d = AppConfig.from(Map.of());
        assertEquals(5, d.breakerFailures());
        assertEquals(5_000, d.breakerCooldownMs());
        assertEquals(1_000, d.deferDelayMs());
        AppConfig c = AppConfig.from(Map.of("BREAKER_FAILURES", "8", "BREAKER_COOLDOWN_MS", "250", "DEFER_DELAY_MS", "50"));
        assertEquals(8, c.breakerFailures());
        assertEquals(250, c.breakerCooldownMs());
        assertEquals(50, c.deferDelayMs());
    }
}

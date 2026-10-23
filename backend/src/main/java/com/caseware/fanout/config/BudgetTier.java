package com.caseware.fanout.config;

public enum BudgetTier {
    /** Event-driven fan-out, rule-based summaries, no LLM. */
    LEAN,
    /** LEAN + one cached LLM narrative per (product, from→to) version pair. */
    TARGET,
    /** Hard ceiling: cost alarms + kill-switch that degrades to LEAN. */
    CAP
}

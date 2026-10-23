package com.caseware.fanout.config;

/** The brief leaves $X blank: monthly all-in cap (USD) per scenario. Assumptions. */
public enum BudgetScenario {
    OPTIMISTIC(10_000),
    MID(3_000),
    PESSIMISTIC(1_000);

    public final int monthlyCapUsd;

    BudgetScenario(int monthlyCapUsd) {
        this.monthlyCapUsd = monthlyCapUsd;
    }
}

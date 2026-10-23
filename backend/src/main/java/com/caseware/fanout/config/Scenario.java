package com.caseware.fanout.config;

import java.time.Duration;

public enum Scenario {
    OPTIMISTIC(new ScaleProfile(4_000, 800_000, 40, 40, 40_000, Duration.ofMinutes(1), Duration.ofDays(7), 200, 1.0)),
    MID(new ScaleProfile(6_000, 1_200_000, 40, 40, 40_000, Duration.ofMinutes(1), Duration.ofDays(7), 100, 0.5)),
    PESSIMISTIC(new ScaleProfile(12_000, 2_400_000, 40, 40, 40_000, Duration.ofMinutes(1), Duration.ofDays(7), 50, 0.0));

    public final ScaleProfile profile;

    Scenario(ScaleProfile profile) {
        this.profile = profile;
    }
}

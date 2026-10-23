package com.caseware.fanout.config;

import java.time.Duration;

/** Estate size + downstream capacity. All numbers are assumptions except OPTIMISTIC (the brief). */
public record ScaleProfile(
        int firms,
        int activeEngagements,
        int products,
        int medianEngagementsPerFirm,
        int maxEngagementsPerFirm,
        Duration engagementLoadTime,
        Duration publishCadencePerProduct,
        int downstreamPermits,
        /** Share of engagement IDs listable without a load (0..1). Version read always needs the load. */
        double enumerationCoverage) {

    /** Wall-clock to load every existing engagement once, at full permit use. */
    public Duration backfillDuration() {
        return engagementLoadTime.multipliedBy(activeEngagements).dividedBy(downstreamPermits);
    }

    /** Publishes per month (52 weeks / 12) across all products: 40 x 52/12 = ~173. */
    public long publishesPerMonth() {
        double monthSeconds = Duration.ofDays(364).toSeconds() / 12.0;
        return Math.round(products * monthSeconds / publishCadencePerProduct.toSeconds());
    }
}

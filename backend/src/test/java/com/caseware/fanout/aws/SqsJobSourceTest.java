package com.caseware.fanout.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SqsJobSourceTest {
    @Test
    void subSecondDelaysRoundUpNotToZero() {
        assertEquals(1, SqsJobSource.delaySeconds(Duration.ofMillis(200)));
        assertEquals(1, SqsJobSource.delaySeconds(Duration.ofMillis(1000)));
        assertEquals(2, SqsJobSource.delaySeconds(Duration.ofMillis(1001)));
        assertEquals(900, SqsJobSource.delaySeconds(Duration.ofHours(1)));
    }
}

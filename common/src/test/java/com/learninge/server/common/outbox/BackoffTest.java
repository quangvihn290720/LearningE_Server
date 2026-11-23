package com.learninge.server.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void doublesUpToCap() {
        Backoff middle = new Backoff(15_000, () -> 0.5);
        assertThat(IntStream.rangeClosed(1, 6).mapToLong(middle::delayMillis))
                .containsExactly(2000L, 4000L, 8000L, 15000L, 15000L, 15000L);
        assertThat(middle.delayMillis(1_000_000)).isEqualTo(15000);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(new Backoff(15_000, () -> 0.0).delayMillis(1)).isEqualTo(1600);
        assertThat(new Backoff(15_000, () -> 1.0).delayMillis(1)).isEqualTo(2400);
    }
}

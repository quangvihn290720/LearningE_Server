package com.learninge.server.gamification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.server.gamification.domain.WeeklyTotal.Entry;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class WeeklyTotalTest {
    private static Entry entry(String day, int xp, int second) {
        return new Entry(day, xp, Instant.ofEpochSecond(second));
    }

    @Test
    void capsEachDaySeparately() {
        assertThat(
                WeeklyTotal.of(
                                List.of(
                                        entry("2026-11-09", 400, 1),
                                        entry("2026-11-09", 400, 2),
                                        entry("2026-11-10", 30, 3)))
                        .xp())
                .isEqualTo(530);
    }

    @Test
    void reachedAtIgnoresEventsWithoutXp() {
        assertThat(WeeklyTotal.of(List.of(entry("2026-11-09", 5, 10), entry("2026-11-09", 0, 99))))
                .isEqualTo(new WeeklyTotal(5, Instant.ofEpochSecond(10)));
        assertThat(WeeklyTotal.of(List.of())).isEqualTo(new WeeklyTotal(0, Instant.EPOCH));
    }
}

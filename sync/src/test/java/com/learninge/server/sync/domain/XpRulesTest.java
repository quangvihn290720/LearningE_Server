package com.learninge.server.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class XpRulesTest {
    private static final long NOW = 1_700_000_000_000L;

    private static int xp(XpSourceType type, int correct, int wrong) {
        return XpRules.baseXp(event(type, correct, wrong, correct + wrong, NOW), NOW);
    }

    private static XpEventData event(XpSourceType type, int correct, int wrong, int total, long at) {
        return XpEventData.newBuilder()
                .setSourceType(type)
                .setCorrectCount(correct)
                .setWrongCount(wrong)
                .setTotalCount(total)
                .setOccurredAtMs(at)
                .setXpAwarded(9999)
                .build();
    }

    @Test
    void followsBusinessRuleExamples() {
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_CARD_SESSION, 7, 3)).isEqualTo(17);
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_QUIZ, 10, 0)).isEqualTo(15);
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_QUIZ, 7, 3)).isEqualTo(7);
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_EXAM, 16, 4)).isEqualTo(16);
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_EXAM, 20, 0)).isEqualTo(20);
    }

    @Test
    void absurdEventsEarnNothing() {
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_CARD_SESSION, 50, 0)).isEqualTo(100);
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_CARD_SESSION, 51, 0)).isZero();
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_QUIZ, 11, 0)).isZero();
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_EXAM, 21, 0)).isZero();
        assertThat(xp(XpSourceType.XP_SOURCE_TYPE_QUIZ, 0, 0)).isZero();
        assertThat(XpRules.baseXp(event(XpSourceType.XP_SOURCE_TYPE_QUIZ, 5, 0, 3, NOW), NOW)).isZero();
    }

    @Test
    void acceptsSevenDaysBackToFiveMinutesAhead() {
        long week = Duration.ofDays(7).toMillis();
        long five = Duration.ofMinutes(5).toMillis();
        assertThat(at(NOW - week)).isPositive();
        assertThat(at(NOW - week - 1)).isZero();
        assertThat(at(NOW + five)).isPositive();
        assertThat(at(NOW + five + 1)).isZero();
    }

    private static int at(long occurredAt) {
        return XpRules.baseXp(event(XpSourceType.XP_SOURCE_TYPE_EXAM, 5, 0, 5, occurredAt), NOW);
    }

    @Test
    void capsAtFiveHundredPerDay() {
        assertThat(XpRules.capped(100, 0)).isEqualTo(100);
        assertThat(XpRules.capped(100, 450)).isEqualTo(50);
        assertThat(XpRules.capped(100, 500)).isZero();
        assertThat(XpRules.capped(100, 600)).isZero();
    }

    @Test
    void farFutureEventsCountForTheReceivingDay() {
        assertThat(XpRules.localDate(NOW + Duration.ofDays(400).toMillis(), NOW))
                .isEqualTo(LocalDate.of(2023, 11, 15));
    }
}

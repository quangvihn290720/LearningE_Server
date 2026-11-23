package com.learninge.server.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class EmailKeysTest {
    private final EmailKeys keys = new EmailKeys(new byte[32]);

    @Test
    void fakeQuestionIsStableInRangeAndSpread() {
        assertThat(keys.fakeQuestionId("a@b.co"))
                .isEqualTo(new EmailKeys(new byte[32]).fakeQuestionId("a@b.co"));
        assertThat(
                IntStream.range(0, 200).map(i -> keys.fakeQuestionId(i + "@b.co")).distinct().sorted())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void subjectHidesTheEmail() {
        assertThat(keys.attemptSubject("a@b.co")).hasSize(64).doesNotContain("a@b.co");
    }
}

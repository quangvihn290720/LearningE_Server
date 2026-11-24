package com.learninge.server.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.app.v1.ActivityType;
import com.learninge.proto.app.v1.CardProgressData;
import com.learninge.proto.app.v1.CefrLevel;
import com.learninge.proto.app.v1.DeckData;
import com.learninge.proto.app.v1.ExamAttemptData;
import com.learninge.proto.app.v1.FavoriteData;
import com.learninge.proto.app.v1.QuizAttemptData;
import com.learninge.proto.app.v1.QuizSourceType;
import com.learninge.proto.app.v1.SettingsData;
import com.learninge.proto.app.v1.StudyLogData;
import com.learninge.proto.app.v1.WordData;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;

import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * Ràng buộc từng loại bản ghi (SRS §Validation): bắt đầu từ một bản hợp lệ, đổi đúng một trường.
 */
class RecordRulesTest {
    private static final String UUID = "aaaaaaaa-0000-4000-8000-000000000001";

    private static boolean settings(UnaryOperator<SettingsData.Builder> change) {
        return RecordRules.settings(
                change.apply(
                                SettingsData.newBuilder().setLevel(CefrLevel.CEFR_LEVEL_A1).setDailyGoal(10))
                        .build());
    }

    private static boolean deck(UnaryOperator<DeckData.Builder> change) {
        return RecordRules.deck(
                change.apply(
                                DeckData.newBuilder()
                                        .setName("Từ của tôi")
                                        .setLanguageCode("en")
                                        .setCreatedAtMs(1))
                        .build());
    }

    private static boolean word(UnaryOperator<WordData.Builder> change) {
        return RecordRules.word(
                change.apply(
                                WordData.newBuilder()
                                        .setDeckUuid(UUID)
                                        .setTerm("cat")
                                        .setMeaningVi("mèo")
                                        .setCreatedAtMs(1))
                        .build());
    }

    private static boolean card(UnaryOperator<CardProgressData.Builder> change) {
        return RecordRules.cardProgress(
                change.apply(
                                CardProgressData.newBuilder()
                                        .setBox(1)
                                        .setNextReviewDate("2026-11-24")
                                        .setFirstLearnedDate("2026-11-24"))
                        .build());
    }

    private static boolean log(UnaryOperator<StudyLogData.Builder> change) {
        return RecordRules.studyLog(
                change.apply(
                                StudyLogData.newBuilder()
                                        .setActivityType(ActivityType.ACTIVITY_TYPE_LEARN)
                                        .setStudyDate("2026-11-24")
                                        .setCreatedAtMs(1))
                        .build());
    }

    private static boolean quiz(UnaryOperator<QuizAttemptData.Builder> change) {
        return RecordRules.quizAttempt(
                change.apply(
                                QuizAttemptData.newBuilder()
                                        .setSourceType(QuizSourceType.QUIZ_SOURCE_TYPE_DECK)
                                        .setScore(100)
                                        .setCorrectCount(10)
                                        .setTotalCount(10)
                                        .setCreatedAtMs(1))
                        .build());
    }

    private static boolean exam(UnaryOperator<ExamAttemptData.Builder> change) {
        return RecordRules.examAttempt(
                change.apply(
                                ExamAttemptData.newBuilder()
                                        .setTestCode("E01")
                                        .setScore(50)
                                        .setCorrectCount(10)
                                        .setTotalCount(20)
                                        .setStartedAtMs(5)
                                        .setSubmittedAtMs(5))
                        .build());
    }

    private static boolean xp(UnaryOperator<XpEventData.Builder> change) {
        return RecordRules.xpEvent(
                change.apply(
                                XpEventData.newBuilder()
                                        .setSourceType(XpSourceType.XP_SOURCE_TYPE_QUIZ)
                                        .setCorrectCount(1)
                                        .setTotalCount(1)
                                        .setOccurredAtMs(1))
                        .build());
    }

    @Test
    void settings() {
        assertThat(settings(b -> b)).isTrue();
        assertThat(settings(b -> b.setLevel(CefrLevel.CEFR_LEVEL_UNSPECIFIED))).isFalse();
        assertThat(settings(b -> b.setLevelValue(99))).isFalse();
        assertThat(settings(b -> b.setLevelValue(-1))).isFalse();
        for (int goal : new int[]{5, 10, 15, 20}) {
            assertThat(settings(b -> b.setDailyGoal(goal))).isTrue();
        }
        assertThat(settings(b -> b.setDailyGoal(7))).isFalse();
    }

    @Test
    void decks() {
        assertThat(deck(b -> b.setDescription("d".repeat(200)).setIcon("i".repeat(40)))).isTrue();
        assertThat(deck(b -> b.setName("ệ".repeat(50)))).isTrue();
        assertThat(deck(b -> b.setName(""))).isFalse();
        assertThat(deck(b -> b.setName("   "))).isFalse();
        assertThat(deck(b -> b.setName("x".repeat(51)))).isFalse();
        assertThat(deck(b -> b.setName("a\0b"))).isFalse();
        assertThat(deck(b -> b.setDescription("d".repeat(201)))).isFalse();
        assertThat(deck(b -> b.setLanguageCode("eng"))).isFalse();
        assertThat(deck(b -> b.setIcon("i".repeat(41)))).isFalse();
        assertThat(deck(b -> b.setSortOrder(-1))).isFalse();
        assertThat(deck(b -> b.setCreatedAtMs(0))).isFalse();
    }

    @Test
    void words() {
        assertThat(
                word(
                        b ->
                                b.setTerm("😀".repeat(50))
                                        .setReading("r".repeat(60))
                                        .setPartOfSpeech("p".repeat(20))
                                        .setMeaningVi("m".repeat(200))
                                        .setExample("e".repeat(300))
                                        .setExampleTranslation("t".repeat(300))))
                .isTrue();
        assertThat(word(b -> b.setDeckUuid("T01"))).isFalse();
        assertThat(word(b -> b.setTerm(""))).isFalse();
        assertThat(word(b -> b.setTerm(" "))).isFalse();
        assertThat(word(b -> b.setTerm("x".repeat(51)))).isFalse();
        assertThat(word(b -> b.setReading("x".repeat(61)))).isFalse();
        assertThat(word(b -> b.setPartOfSpeech("x".repeat(21)))).isFalse();
        assertThat(word(b -> b.setMeaningVi(""))).isFalse();
        assertThat(word(b -> b.setMeaningVi(" "))).isFalse();
        assertThat(word(b -> b.setMeaningVi("x".repeat(201)))).isFalse();
        assertThat(word(b -> b.setExample("x".repeat(301)))).isFalse();
        assertThat(word(b -> b.setExampleTranslation("x".repeat(301)))).isFalse();
        assertThat(word(b -> b.setSortOrder(-1))).isFalse();
        assertThat(word(b -> b.setCreatedAtMs(0))).isFalse();
    }

    @Test
    void cards() {
        assertThat(card(b -> b.setBox(5).setLastReviewedAtMs(1))).isTrue();
        assertThat(card(b -> b.setBox(0))).isFalse();
        assertThat(card(b -> b.setBox(6))).isFalse();
        assertThat(card(b -> b.setNextReviewDate("24/11/2026"))).isFalse();
        assertThat(card(b -> b.setNextReviewDate("2026-02-30"))).isFalse();
        assertThat(card(b -> b.setFirstLearnedDate("2026-1-1"))).isFalse();
        assertThat(card(b -> b.setLastReviewedAtMs(0))).isFalse();
        assertThat(card(b -> b.setReviewCount(-1))).isFalse();
    }

    @Test
    void favorites() {
        assertThat(RecordRules.favorite(FavoriteData.newBuilder().setCreatedAtMs(1).build())).isTrue();
        assertThat(RecordRules.favorite(FavoriteData.getDefaultInstance())).isFalse();
    }

    @Test
    void studyLogs() {
        assertThat(log(b -> b.setRefUuid(UUID))).isTrue();
        assertThat(log(b -> b.setRefUuid("T01"))).isTrue();
        assertThat(log(b -> b.setRefUuid("bậy"))).isFalse();
        assertThat(log(b -> b.setActivityType(ActivityType.ACTIVITY_TYPE_UNSPECIFIED))).isFalse();
        assertThat(log(b -> b.setActivityTypeValue(5))).isFalse();
        assertThat(log(b -> b.setStudyDate("hôm nay"))).isFalse();
        assertThat(log(b -> b.setCreatedAtMs(0))).isFalse();
    }

    @Test
    void quizzes() {
        assertThat(quiz(b -> b.setDeckRef("X01"))).isTrue();
        assertThat(quiz(b -> b.setDeckRef("?"))).isFalse();
        assertThat(quiz(b -> b.setSourceType(QuizSourceType.QUIZ_SOURCE_TYPE_UNSPECIFIED))).isFalse();
        assertThat(quiz(b -> b.setScore(-1))).isFalse();
        assertThat(quiz(b -> b.setScore(101))).isFalse();
        assertThat(quiz(b -> b.setTotalCount(0).setCorrectCount(0))).isFalse();
        assertThat(quiz(b -> b.setTotalCount(201))).isFalse();
        assertThat(quiz(b -> b.setCorrectCount(-1))).isFalse();
        assertThat(quiz(b -> b.setCorrectCount(11))).isFalse();
        assertThat(quiz(b -> b.setDurationSec(-1))).isFalse();
        assertThat(quiz(b -> b.setCreatedAtMs(0))).isFalse();
    }

    @Test
    void exams() {
        assertThat(exam(b -> b)).isTrue();
        assertThat(exam(b -> b.setScore(101))).isFalse();
        assertThat(exam(b -> b.setDurationSec(-1))).isFalse();
        assertThat(exam(b -> b.setStartedAtMs(0))).isFalse();
        assertThat(exam(b -> b.setSubmittedAtMs(4))).isFalse();
        assertThat(RecordRules.testCode("E01")).isTrue();
        assertThat(RecordRules.testCode("E100")).isTrue();
        assertThat(RecordRules.testCode("e1")).isFalse();
    }

    @Test
    void xpEvents() {
        assertThat(xp(b -> b.setRefUuid("E01").setCorrectCount(200).setWrongCount(200).setTotalCount(200))).isTrue();
        assertThat(xp(b -> b.setSourceType(XpSourceType.XP_SOURCE_TYPE_UNSPECIFIED))).isFalse();
        assertThat(xp(b -> b.setSourceTypeValue(4))).isFalse();
        assertThat(xp(b -> b.setRefUuid("?"))).isFalse();
        assertThat(xp(b -> b.setCorrectCount(201))).isFalse();
        assertThat(xp(b -> b.setCorrectCount(-1))).isFalse();
        assertThat(xp(b -> b.setWrongCount(201))).isFalse();
        assertThat(xp(b -> b.setTotalCount(201))).isFalse();
        assertThat(xp(b -> b.setOccurredAtMs(0))).isFalse();
    }

    @Test
    void wordIds() {
        assertThat(RecordRules.wordUid("T01-0007")).isTrue();
        assertThat(RecordRules.wordUid(UUID)).isTrue();
        assertThat(RecordRules.wordUid("T100-0001")).isTrue();
        assertThat(RecordRules.wordUid("T1000-0001")).isFalse();
        assertThat(RecordRules.wordUid("T1-7")).isFalse();
        assertThat(RecordRules.isUserWord("T01-0007")).isFalse();
    }
}

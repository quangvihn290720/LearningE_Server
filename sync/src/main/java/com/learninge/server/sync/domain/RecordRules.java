package com.learninge.server.sync.domain;

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
import com.learninge.server.common.id.Uuids;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ràng buộc của từng loại bản ghi đồng bộ (SRS §Validation). Hàm thuần; sai thì bản ghi bị {@code
 * REJECTED} nhưng không hủy cả lô.
 */
public final class RecordRules {
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Pattern CONTENT_WORD = Pattern.compile("[A-Z][0-9]{2,3}-[0-9]{4}");
    private static final Pattern TEST_CODE = Pattern.compile("[A-Z][0-9]{2,3}");
    private static final Set<Integer> DAILY_GOALS = Set.of(5, 10, 15, 20);
    private static final int MAX_XP_COUNT = 200;

    private RecordRules() {
    }

    public static boolean settings(SettingsData data) {
        return known(data.getLevel() != CefrLevel.CEFR_LEVEL_UNSPECIFIED, data.getLevelValue(), 3)
                && DAILY_GOALS.contains(data.getDailyGoal());
    }

    public static boolean deck(DeckData data) {
        return text(data.getName(), 1, 50)
                && !data.getName().isBlank()
                && optional(data.hasDescription(), data.getDescription(), 200)
                && LANGUAGE.matcher(data.getLanguageCode()).matches()
                && optional(data.hasIcon(), data.getIcon(), 40)
                && data.getSortOrder() >= 0
                && data.getCreatedAtMs() > 0;
    }

    public static boolean word(WordData data) {
        return Uuids.isCanonical(data.getDeckUuid())
                && text(data.getTerm(), 1, 50)
                && !data.getTerm().isBlank()
                && optional(data.hasReading(), data.getReading(), 60)
                && optional(data.hasPartOfSpeech(), data.getPartOfSpeech(), 20)
                && text(data.getMeaningVi(), 1, 200)
                && !data.getMeaningVi().isBlank()
                && optional(data.hasExample(), data.getExample(), 300)
                && optional(data.hasExampleTranslation(), data.getExampleTranslation(), 300)
                && data.getSortOrder() >= 0
                && data.getCreatedAtMs() > 0;
    }

    public static boolean cardProgress(CardProgressData data) {
        return data.getBox() >= 1
                && data.getBox() <= 5
                && date(data.getNextReviewDate())
                && date(data.getFirstLearnedDate())
                && (!data.hasLastReviewedAtMs() || data.getLastReviewedAtMs() > 0)
                && data.getReviewCount() >= 0;
    }

    public static boolean favorite(FavoriteData data) {
        return data.getCreatedAtMs() > 0;
    }

    public static boolean studyLog(StudyLogData data) {
        boolean type = data.getActivityType() != ActivityType.ACTIVITY_TYPE_UNSPECIFIED;
        return known(type, data.getActivityTypeValue(), 4)
                && optionalRef(data.hasRefUuid(), data.getRefUuid())
                && date(data.getStudyDate())
                && data.getCreatedAtMs() > 0;
    }

    public static boolean quizAttempt(QuizAttemptData data) {
        return data.getSourceType() == QuizSourceType.QUIZ_SOURCE_TYPE_DECK
                && optionalRef(data.hasDeckRef(), data.getDeckRef())
                && score(data.getScore(), data.getCorrectCount(), data.getTotalCount())
                && data.getDurationSec() >= 0
                && data.getCreatedAtMs() > 0;
    }

    public static boolean examAttempt(ExamAttemptData data) {
        return score(data.getScore(), data.getCorrectCount(), data.getTotalCount())
                && data.getDurationSec() >= 0
                && data.getStartedAtMs() > 0
                && data.getSubmittedAtMs() >= data.getStartedAtMs();
    }

    public static boolean testCode(String code) {
        return TEST_CODE.matcher(code).matches();
    }

    /**
     * Số đếm ngoài 0..200 thì từ chối; vô lý nhưng trong khoảng thì lưu với 0 XP (XpRules).
     */
    public static boolean xpEvent(XpEventData data) {
        boolean type = data.getSourceType() != XpSourceType.XP_SOURCE_TYPE_UNSPECIFIED;
        return known(type, data.getSourceTypeValue(), 3)
                && optionalRef(data.hasRefUuid(), data.getRefUuid())
                && count(data.getCorrectCount())
                && count(data.getWrongCount())
                && count(data.getTotalCount())
                && data.getOccurredAtMs() > 0;
    }

    /**
     * {@code T01-0007} (từ có sẵn) hoặc UUID (từ của người dùng).
     */
    public static boolean wordUid(String value) {
        return isUserWord(value) || CONTENT_WORD.matcher(value).matches();
    }

    public static boolean isUserWord(String value) {
        return Uuids.isCanonical(value);
    }

    private static boolean known(boolean specified, int number, int max) {
        return specified && number <= max && number > 0;
    }

    private static boolean count(int value) {
        return value >= 0 && value <= MAX_XP_COUNT;
    }

    private static boolean score(int score, int correct, int total) {
        return score >= 0
                && score <= 100
                && total >= 1
                && total <= 200
                && correct >= 0
                && correct <= total;
    }

    private static boolean optional(boolean present, String value, int max) {
        return !present || text(value, 0, max);
    }

    /**
     * Tham chiếu tới bộ (uuid) hoặc mã nội dung; chỉ lưu, không dùng để truy vấn.
     */
    private static boolean optionalRef(boolean present, String value) {
        return !present || Uuids.isCanonical(value) || TEST_CODE.matcher(value).matches();
    }

    private static boolean text(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max && value.indexOf('\0') < 0;
    }

    private static boolean date(String value) {
        try {
            return value.length() == 10 && LocalDate.parse(value) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

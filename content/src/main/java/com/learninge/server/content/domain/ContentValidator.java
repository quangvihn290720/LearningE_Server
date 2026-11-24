package com.learninge.server.content.domain;

import com.learninge.server.content.domain.ContentBundle.Deck;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Exam;
import com.learninge.server.content.domain.ContentBundle.Language;
import com.learninge.server.content.domain.ContentBundle.Question;
import com.learninge.server.content.domain.ContentBundle.Word;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm toàn bộ nội dung <b>trước</b> khi ghi bất cứ gì. Hàm thuần, gom đủ lỗi để sửa một lượt.
 */
public final class ContentValidator {
    private static final Pattern CODE = Pattern.compile("[A-Z][0-9]{2,3}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Set<String> LEVELS = Set.of("A1", "A2", "B1");
    /**
     * Các kỳ thi app có màn riêng; kỳ thi khác sẽ không hiện ở đâu cả.
     */
    private static final Set<String> EXAM_TAGS = Set.of("TOEIC", "IELTS", "APTIS");

    private final List<String> errors = new ArrayList<>();
    private final ContentBundle bundle;

    private ContentValidator(ContentBundle bundle) {
        this.bundle = bundle;
    }

    /**
     * Trả danh sách lỗi; rỗng nghĩa là hợp lệ.
     */
    public static List<String> validate(ContentBundle bundle) {
        ContentValidator validator = new ContentValidator(bundle);
        validator.run();
        return validator.errors;
    }

    /**
     * Uid của từ mà câu hỏi gắn tới, nếu có.
     */
    public static Optional<String> wordUid(ContentBundle bundle, Question question) {
        if (question.deckCode() == null || question.term() == null) {
            return Optional.empty();
        }
        return bundle.decks().stream()
                .filter(d -> d.deck().code().equals(question.deckCode()))
                .flatMap(
                        d -> {
                            String key = TermKey.of(question.term(), d.deck().language());
                            return d.words().stream()
                                    .filter(w -> TermKey.of(w.term(), d.deck().language()).equals(key));
                        })
                .map(Word::uid)
                .findFirst();
    }

    private void run() {
        Set<String> languages = new HashSet<>();
        for (Language language : bundle.languages()) {
            check(matches(LANGUAGE, language.code()), "ngôn ngữ", "code sai: " + language.code());
            check(languages.add(language.code()), "ngôn ngữ", "trùng " + language.code());
            required("ngôn ngữ " + language.code(), "name_vi", language.nameVi(), 40);
            required("ngôn ngữ " + language.code(), "tts_locale", language.ttsLocale(), 10);
            required("ngôn ngữ " + language.code(), "reading_label", language.readingLabel(), 40);
        }
        Set<String> codes = new HashSet<>();
        Set<String> uids = new HashSet<>();
        for (DeckFile file : bundle.decks()) {
            deck(file.deck(), languages, codes);
            words(file, uids);
        }
        for (Exam exam : bundle.exams()) {
            exam(exam, codes);
        }
    }

    private void deck(Deck deck, Set<String> languages, Set<String> codes) {
        String where = "bộ " + deck.code();
        check(matches(CODE, deck.code()), where, "code phải dạng T01");
        check(codes.add(deck.code()), where, "trùng code");
        check(deck.version() >= 1, where, "thiếu version");
        required(where, "name", deck.name(), 50);
        optional(where, "description", deck.description(), 200);
        optional(where, "icon", deck.icon(), 40);
        check(deck.examTag() == null || EXAM_TAGS.contains(deck.examTag()), where, "exam_tag: " + deck.examTag());
        check(languages.contains(deck.language()), where, "ngôn ngữ lạ: " + deck.language());
        check(deck.level() == null || LEVELS.contains(deck.level()), where, "level: " + deck.level());
    }

    private void words(DeckFile file, Set<String> uids) {
        Set<String> terms = new HashSet<>();
        Pattern uidPattern = Pattern.compile(Pattern.quote(file.deck().code()) + "-[0-9]{4}");
        required("bộ " + file.deck().code(), "source", file.source(), 200);
        check(!file.words().isEmpty(), "bộ " + file.deck().code(), "không có từ");
        for (Word word : file.words()) {
            String where = "bộ " + file.deck().code() + " từ " + word.term();
            check(matches(uidPattern, word.uid()), where, "uid sai: " + word.uid());
            check(uids.add(word.uid()), where, "trùng uid " + word.uid());
            required(where, "term", word.term(), 50);
            check(word.term() == null || word.term().chars().noneMatch(Character::isISOControl), where, "term có ký tự lạ");
            required(where, "meaning", word.meaning(), 200);
            optional(where, "reading", word.reading(), 60);
            optional(where, "pos", word.pos(), 20);
            optional(where, "example", word.example(), 300);
            optional(where, "example_translation", word.exampleTranslation(), 300);
            if (word.term() != null) {
                // Cùng khóa với index duy nhất của app: lọt một cặp là app hủy cả lần cập nhật.
                check(
                        terms.add(TermKey.of(word.term(), file.deck().language())),
                        where,
                        "trùng từ trong bộ (không kể hoa thường, dấu)");
            }
        }
    }

    private void exam(Exam exam, Set<String> codes) {
        String where = "đề " + exam.code();
        check(matches(CODE, exam.code()), where, "code phải dạng E01");
        check(codes.add(exam.code()), where, "trùng code");
        check(exam.version() >= 1, where, "thiếu version");
        required(where, "title", exam.title(), 100);
        check(exam.examTag() != null && EXAM_TAGS.contains(exam.examTag()), where, "exam_tag: " + exam.examTag());
        optional(where, "description", exam.description(), 300);
        check(exam.durationSec() >= 60, where, "duration_sec phải từ 60");
        int count = exam.questions().size();
        check(count >= 1 && count <= 50, where, "phải có 1..50 câu");
        for (int i = 0; i < count; i++) {
            Question question = exam.questions().get(i);
            // App cũ nhận ra câu bị bỏ bằng số câu, nên số câu phải liên tục.
            check(question.number() == i + 1, where, "câu thứ " + (i + 1) + " phải có number " + (i + 1));
            question(where + " câu " + question.number(), question);
        }
    }

    private void question(String where, Question question) {
        required(where, "question", question.question(), 500);
        optional(where, "passage", question.passage(), 2000);
        optional(where, "lựa chọn", longest(question.options()), 200);
        optional(where, "explanation", question.explanation(), 500);
        List<String> options = question.options() == null ? List.of() : question.options();
        check(
                options.size() == 4 && options.stream().allMatch(o -> o != null && !o.isBlank()),
                where,
                "phải có 4 lựa chọn");
        check(new HashSet<>(options).size() == options.size(), where, "các lựa chọn phải khác nhau");
        check(question.correctIndex() >= 0 && question.correctIndex() <= 3, where, "correct_index sai");
        boolean linked = question.deckCode() != null || question.term() != null;
        check(!linked || wordUid(bundle, question).isPresent(), where, "không tìm thấy từ được gắn");
    }

    private static String longest(List<String> options) {
        return options == null
                ? null
                : options.stream().filter(Objects::nonNull).max(Comparator.comparingInt(String::length)).orElse(null);
    }

    private void required(String where, String field, String value, int max) {
        check(value != null && !value.isBlank(), where, "thiếu " + field);
        optional(where, field, value, max);
    }

    private void optional(String where, String field, String value, int max) {
        check(value == null || value.equals(value.strip()), where, field + " thừa khoảng trắng đầu/cuối");
        check(value == null || value.length() <= max, where, field + " dài quá " + max);
    }

    private void check(boolean valid, String where, String message) {
        if (!valid) {
            errors.add(where + ": " + message);
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
}

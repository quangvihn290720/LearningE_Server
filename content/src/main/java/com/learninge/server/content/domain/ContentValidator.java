package com.learninge.server.content.domain;

import com.learninge.server.content.domain.ContentBundle.Deck;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Exam;
import com.learninge.server.content.domain.ContentBundle.Language;
import com.learninge.server.content.domain.ContentBundle.Question;
import com.learninge.server.content.domain.ContentBundle.Word;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Kiểm toàn bộ nội dung <b>trước</b> khi ghi bất cứ gì. Hàm thuần, gom đủ lỗi để sửa một lượt. */
public final class ContentValidator {
  private static final Pattern CODE = Pattern.compile("[A-Z][0-9]{2}");
  private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
  private static final Set<String> LEVELS = Set.of("A1", "A2", "B1");

  private final List<String> errors = new ArrayList<>();
  private final ContentBundle bundle;

  private ContentValidator(ContentBundle bundle) {
    this.bundle = bundle;
  }

  /** Trả danh sách lỗi; rỗng nghĩa là hợp lệ. */
  public static List<String> validate(ContentBundle bundle) {
    ContentValidator validator = new ContentValidator(bundle);
    validator.run();
    return validator.errors;
  }

  /** Uid của từ mà câu hỏi gắn tới, nếu có. */
  public static Optional<String> wordUid(ContentBundle bundle, Question question) {
    if (question.deckCode() == null || question.term() == null) {
      return Optional.empty();
    }
    return bundle.decks().stream()
        .filter(d -> d.deck().code().equals(question.deckCode()))
        .flatMap(d -> d.words().stream())
        .filter(w -> normalize(w.term()).equals(normalize(question.term())))
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
    optional(where, "exam_tag", deck.examTag(), 20);
    check(languages.contains(deck.language()), where, "ngôn ngữ lạ: " + deck.language());
    check(deck.level() == null || LEVELS.contains(deck.level()), where, "level: " + deck.level());
  }

  private void words(DeckFile file, Set<String> uids) {
    Set<String> terms = new HashSet<>();
    Pattern uidPattern = Pattern.compile(Pattern.quote(file.deck().code()) + "-[0-9]{4}");
    check(!file.words().isEmpty(), "bộ " + file.deck().code(), "không có từ");
    for (Word word : file.words()) {
      String where = "bộ " + file.deck().code() + " từ " + word.term();
      check(matches(uidPattern, word.uid()), where, "uid sai: " + word.uid());
      check(uids.add(word.uid()), where, "trùng uid " + word.uid());
      required(where, "term", word.term(), 100);
      required(where, "meaning", word.meaning(), 200);
      optional(where, "reading", word.reading(), 100);
      optional(where, "pos", word.pos(), 20);
      optional(where, "example", word.example(), 300);
      optional(where, "example_translation", word.exampleTranslation(), 300);
      if (word.term() != null) {
        check(terms.add(normalize(word.term())), where, "trùng từ trong bộ");
      }
    }
  }

  private void exam(Exam exam, Set<String> codes) {
    String where = "đề " + exam.code();
    check(matches(CODE, exam.code()), where, "code phải dạng E01");
    check(codes.add(exam.code()), where, "trùng code");
    check(exam.version() >= 1, where, "thiếu version");
    required(where, "title", exam.title(), 100);
    optional(where, "description", exam.description(), 300);
    check(exam.durationSec() >= 60, where, "duration_sec phải từ 60");
    int count = exam.questions().size();
    check(count >= 1 && count <= 50, where, "phải có 1..50 câu");
    Set<Integer> numbers = new HashSet<>();
    for (Question question : exam.questions()) {
      question(where + " câu " + question.number(), question, numbers);
    }
  }

  private void question(String where, Question question, Set<Integer> numbers) {
    check(question.number() >= 1 && numbers.add(question.number()), where, "số câu sai hoặc trùng");
    required(where, "question", question.question(), 500);
    optional(where, "passage", question.passage(), 2000);
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

  private void required(String where, String field, String value, int max) {
    check(value != null && !value.isBlank(), where, "thiếu " + field);
    optional(where, field, value, max);
  }

  private void optional(String where, String field, String value, int max) {
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

  private static String normalize(String term) {
    return Objects.requireNonNullElse(term, "").strip().toLowerCase(Locale.ROOT);
  }
}

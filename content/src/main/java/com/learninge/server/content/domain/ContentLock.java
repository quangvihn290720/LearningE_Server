package com.learninge.server.content.domain;

import com.learninge.server.content.domain.ContentBundle.Deck;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Exam;
import com.learninge.server.content.domain.ContentBundle.Question;
import com.learninge.server.content.domain.ContentBundle.Word;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@code content.lock}: phiên bản và mã băm của mọi bộ/đề, cùng mọi uid <b>từng</b> dùng. Nhờ nó mà quên tăng version,
 * xóa file hay dùng lại uid cũ bị bắt ngay ở máy người soạn và CI, trước khi tới máy người học.
 */
public record ContentLock(SortedMap<String, Item> items, SortedMap<String, LockedWord> words) {
    private static final String HEADER = "# content.lock — tự sinh bằng tools/content.ps1 lock, không sửa tay\n";

    /**
     * {@code kind}: {@code deck} hoặc {@code exam}.
     */
    public record Item(String kind, int version, String hash) {
    }

    public record LockedWord(boolean active, String term) {
    }

    /**
     * Khóa mới cùng lỗi (phải sửa) và cảnh báo (nên xem lại).
     */
    public record Update(ContentLock lock, List<String> errors, List<String> warnings) {
    }

    public static ContentLock parse(String text) {
        SortedMap<String, Item> items = new TreeMap<>();
        SortedMap<String, LockedWord> words = new TreeMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\t");
            if (cells.length == 4 && (cells[0].equals("deck") || cells[0].equals("exam"))) {
                items.put(cells[1], new Item(cells[0], Integer.parseInt(cells[2]), cells[3]));
            } else if (cells.length == 3 && (cells[0].equals("word") || cells[0].equals("removed"))) {
                words.put(cells[1], new LockedWord(cells[0].equals("word"), cells[2]));
            } else {
                throw new IllegalArgumentException("content.lock có dòng lạ: " + line);
            }
        }
        return new ContentLock(items, words);
    }

    public String render() {
        StringBuilder text = new StringBuilder(HEADER);
        items.forEach(
                (code, item) ->
                        text.append(String.join(
                                "\t", item.kind(), code, String.valueOf(item.version()), item.hash())).append('\n'));
        words.forEach(
                (uid, word) ->
                        text.append(String.join("\t", word.active() ? "word" : "removed", uid, word.term()))
                                .append('\n'));
        return text.toString();
    }

    /**
     * So nội dung (đã qua {@link ContentValidator}) với khóa này.
     */
    public Update update(ContentBundle bundle) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SortedMap<String, Item> newItems = new TreeMap<>();
        for (DeckFile file : bundle.decks()) {
            newItems.put(file.deck().code(), new Item("deck", file.deck().version(), hash(file)));
        }
        for (Exam exam : bundle.exams()) {
            newItems.put(exam.code(), new Item("exam", exam.version(), hash(exam)));
        }
        items.forEach((code, old) -> checkItem(code, old, newItems.get(code), errors));

        SortedMap<String, LockedWord> newWords = new TreeMap<>();
        words.forEach((uid, word) -> newWords.put(uid, new LockedWord(false, word.term())));
        for (DeckFile file : bundle.decks()) {
            for (Word word : file.words()) {
                LockedWord old = words.get(word.uid());
                if (old != null && !sameTerm(old.term(), word.term(), file.deck().language())) {
                    String change = word.uid() + ": \"" + old.term() + "\" → \"" + word.term() + "\"";
                    if (old.active()) {
                        warnings.add(change + " — nếu là từ khác hẳn thì dùng uid mới");
                    } else {
                        errors.add(change + " — uid của từ đã xóa không được dùng lại cho từ khác");
                    }
                }
                newWords.put(word.uid(), new LockedWord(true, word.term()));
            }
        }
        return new Update(new ContentLock(newItems, newWords), errors, warnings);
    }

    /**
     * Số thứ tự kế tiếp chưa từng dùng trong bộ.
     */
    public int nextNumber(String deckCode) {
        return words.keySet().stream()
                .filter(uid -> uid.startsWith(deckCode + "-"))
                .mapToInt(uid -> Integer.parseInt(uid.substring(deckCode.length() + 1)))
                .max()
                .orElse(0) + 1;
    }

    private static void checkItem(String code, Item old, Item now, List<String> errors) {
        if (now == null) {
            errors.add(code + ": không được xóa hẳn; giữ lại và đặt \"retired\": true, tăng version");
        } else if (now.version() < old.version()) {
            errors.add(code + ": version lùi từ " + old.version() + " về " + now.version());
        } else if (now.version() == old.version() && !now.hash().equals(old.hash())) {
            errors.add(code + ": nội dung đã đổi, phải tăng version lên " + (old.version() + 1));
        }
    }

    private static boolean sameTerm(String a, String b, String language) {
        return TermKey.of(a, language).equals(TermKey.of(b, language));
    }

    /**
     * Băm những gì app nhận được; {@code version}, {@code source}, {@code name_en} không tính.
     */
    public static String hash(DeckFile file) {
        Deck deck = file.deck();
        Fields fields = new Fields()
                .add("name", deck.name())
                .add("description", deck.description())
                .add("language", deck.language())
                .add("icon", deck.icon())
                .add("level", deck.level())
                .add("exam_tag", deck.examTag())
                .add("sort_order", deck.sortOrder())
                .add("retired", deck.isRetired() ? true : null);
        for (Word word : file.words()) {
            fields.add("uid", word.uid())
                    .add("term", word.term())
                    .add("reading", word.reading())
                    .add("pos", word.pos())
                    .add("meaning", word.meaning())
                    .add("example", word.example())
                    .add("example_translation", word.exampleTranslation());
        }
        return fields.sha256();
    }

    public static String hash(Exam exam) {
        Fields fields = new Fields()
                .add("exam_tag", exam.examTag())
                .add("title", exam.title())
                .add("part_label", exam.partLabel())
                .add("duration_sec", exam.durationSec())
                .add("description", exam.description())
                .add("sort_order", exam.sortOrder())
                .add("retired", exam.isRetired() ? true : null);
        for (Question question : exam.questions()) {
            fields.add("number", question.number())
                    .add("passage", question.passage())
                    .add("question", question.question());
            question.options().forEach(option -> fields.add("option", option));
            fields.add("correct_index", question.correctIndex())
                    .add("explanation", question.explanation())
                    .add("deck_code", question.deckCode())
                    .add("term", question.term());
        }
        return fields.sha256();
    }

    /**
     * Ghép trường có độ dài đứng trước nên không nhập nhằng; bỏ qua trường rỗng để thêm trường mới không đổi mã băm cũ.
     */
    private static final class Fields {
        private final StringBuilder text = new StringBuilder();

        Fields add(String name, Object value) {
            if (value != null) {
                String content = value.toString();
                text.append(name).append(':').append(content.length()).append(':').append(content).append('\n');
            }
            return this;
        }

        String sha256() {
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Khóa rỗng của repo chưa có nội dung.
     */
    public static ContentLock empty() {
        return new ContentLock(new TreeMap<>(), new TreeMap<>());
    }
}

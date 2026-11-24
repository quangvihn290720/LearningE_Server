package com.learninge.server.content.tool;

import com.learninge.server.content.domain.ContentBundle;
import com.learninge.server.content.domain.ContentBundle.Deck;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Word;
import com.learninge.server.content.domain.ContentLock;
import com.learninge.server.content.domain.ContentValidator;
import com.learninge.server.content.service.ContentReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.json.JsonMapper;

/**
 * Công cụ của người soạn nội dung ({@code tools/content.ps1}), chạy trên file, không cần máy chủ hay MongoDB.
 */
public final class ContentTool {
    private static final String LOCK = "content.lock";
    private static final String SAMPLE = "Dữ liệu mẫu";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String USAGE = """
            check              kiểm nội dung và content.lock (CI cũng chạy)
            lock               kiểm rồi ghi lại content.lock
            export T05 a.csv   xuất danh sách từ ra CSV
            import T05 a.csv   thay danh sách từ bằng CSV, tự cấp uid, tăng version và ghi lock
            new T16 a.csv      tạo bộ mới từ CSV; điền tên trong JSON rồi chạy lock""";

    private final Path directory;
    private final PrintStream out;

    public ContentTool(Path directory, PrintStream out) {
        this.directory = directory;
        this.out = out;
    }

    /**
     * {@code <lệnh> [tham số]}; thư mục ở {@code -Dcontent.dir}.
     */
    public static void main(String[] args) throws IOException {
        try {
            new ContentTool(Path.of(System.getProperty("content.dir")), System.out).run(List.of(args));
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.exit(1);
        }
    }

    public void run(List<String> args) throws IOException {
        String command = args.isEmpty() ? "" : args.getFirst();
        switch (command + "/" + args.size()) {
            case "check/1" -> check();
            case "lock/1" -> lock();
            case "export/3" -> exportCsv(args.get(1), Path.of(args.get(2)));
            case "import/3" -> importCsv(args.get(1), Path.of(args.get(2)));
            case "new/3" -> create(args.get(1), Path.of(args.get(2)));
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    private void check() throws IOException {
        ContentLock old = oldLock();
        ContentLock.Update update = verified(ContentReader.read(directory), old);
        if (!update.lock().equals(old)) {
            throw new IllegalArgumentException("content.lock chưa khớp nội dung; chạy tools/content.ps1 lock");
        }
        out.println("Nội dung hợp lệ.");
    }

    private void lock() throws IOException {
        ContentLock.Update update = verified(ContentReader.read(directory), oldLock());
        write(update.lock());
    }

    private void exportCsv(String code, Path csv) throws IOException {
        DeckFile file = deck(ContentReader.read(directory), code);
        Files.writeString(csv, DeckCsv.format(file.words()));
        out.println("Đã xuất " + file.words().size() + " từ ra " + csv);
    }

    private void importCsv(String code, Path csv) throws IOException {
        ContentBundle bundle = ContentReader.read(directory);
        DeckFile old = deck(bundle, code);
        ContentLock lock = oldLock();
        List<Word> words = withUids(code, DeckCsv.parse(read(csv)), lock, old);
        DeckFile draft = new DeckFile(old.deck(), old.source(), words);
        ContentLock.Item locked = lock.items().get(code);
        int version = old.deck().version();
        if (locked != null && !ContentLock.hash(draft).equals(locked.hash())) {
            version = Math.max(version, locked.version() + 1);
        }
        DeckFile changed = new DeckFile(withVersion(old.deck(), version), old.source(), words);
        List<DeckFile> decks = bundle.decks().stream().map(d -> d == old ? changed : d).toList();
        ContentLock.Update update = verified(new ContentBundle(bundle.languages(), decks, bundle.exams()), lock);

        report(old.words(), words);
        Files.writeString(deckPath(code), json(changed));
        out.println(code + " version " + version);
        write(update.lock());
    }

    private void create(String code, Path csv) throws IOException {
        if (!code.matches("[A-Z][0-9]{2,3}") || Files.exists(deckPath(code))) {
            throw new IllegalArgumentException(code + ": mã phải dạng T16 và chưa có");
        }
        ContentBundle bundle = ContentReader.read(directory);
        int order = bundle.decks().stream().mapToInt(d -> d.deck().sortOrder()).max().orElse(0) + 1;
        Deck deck = new Deck(code, 1, "", "", null, "en", null, code.startsWith("T") ? "A1" : null, null, order, null);
        DeckFile empty = new DeckFile(deck, "", List.of());
        List<Word> words = withUids(code, DeckCsv.parse(read(csv)), oldLock(), empty);
        Files.writeString(deckPath(code), json(new DeckFile(deck, "", words)));
        out.println("Đã tạo " + deckPath(code) + ": điền name, name_en, description, level, source rồi chạy lock");
    }

    private ContentLock.Update verified(ContentBundle bundle, ContentLock old) {
        failIfAny(ContentValidator.validate(bundle));
        ContentLock.Update update = old.update(bundle);
        failIfAny(update.errors());
        update.warnings().forEach(w -> out.println("Xem lại: " + w));
        List<String> samples = bundle.decks().stream()
                .filter(d -> d.source() != null && d.source().startsWith(SAMPLE))
                .map(d -> d.deck().code())
                .toList();
        if (!samples.isEmpty()) {
            out.println("Còn là dữ liệu mẫu: " + samples);
        }
        return update;
    }

    private static List<Word> withUids(String code, List<Word> words, ContentLock lock, DeckFile old) {
        int next = lock.nextNumber(code);
        for (Word word : old.words()) {
            next = Math.max(next, number(code, word.uid()) + 1);
        }
        for (Word word : words) {
            if (word.uid() != null) {
                next = Math.max(next, number(code, word.uid()) + 1);
            }
        }
        List<Word> result = new ArrayList<>();
        for (Word w : words) {
            String uid = w.uid() != null ? w.uid() : "%s-%04d".formatted(code, next++);
            result.add(
                    new Word(uid, w.term(), w.reading(), w.pos(), w.meaning(), w.example(), w.exampleTranslation()));
        }
        return result;
    }

    private static int number(String code, String uid) {
        if (!uid.matches(code + "-[0-9]{4}")) {
            throw new IllegalArgumentException("uid sai: " + uid + " (từ mới thì để trống ô uid)");
        }
        return Integer.parseInt(uid.substring(code.length() + 1));
    }

    private void report(List<Word> before, List<Word> after) {
        Map<String, Word> old = byUid(before);
        Map<String, Word> now = byUid(after);
        now.forEach(
                (uid, word) -> {
                    if (!old.containsKey(uid)) {
                        out.println("+ " + uid + " " + word.term());
                    } else if (!old.get(uid).equals(word)) {
                        out.println("~ " + uid + " " + word.term());
                    }
                });
        old.forEach(
                (uid, word) -> {
                    if (!now.containsKey(uid)) {
                        out.println("- " + uid + " " + word.term());
                    }
                });
    }

    private static Map<String, Word> byUid(List<Word> words) {
        return words.stream()
                .collect(Collectors.toMap(Word::uid, Function.identity(), (a, b) -> a, TreeMap::new));
    }

    private static DeckFile deck(ContentBundle bundle, String code) {
        return bundle.decks().stream()
                .filter(d -> d.deck().code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không có bộ " + code));
    }

    private static Deck withVersion(Deck d, int version) {
        return new Deck(
                d.code(), version, d.name(), d.nameEn(), d.description(), d.language(), d.icon(), d.level(),
                d.examTag(), d.sortOrder(), d.retired());
    }

    /**
     * Mỗi từ một dòng để diff trong PR đọc được.
     */
    static String json(DeckFile file) {
        Deck d = file.deck();
        List<String> fields = new ArrayList<>();
        field(fields, "code", d.code(), true);
        field(fields, "version", d.version(), true);
        field(fields, "name", d.name(), true);
        field(fields, "name_en", d.nameEn(), true);
        field(fields, "description", d.description(), true);
        field(fields, "language", d.language(), true);
        field(fields, "icon", d.icon(), true);
        field(fields, "level", d.level(), true);
        field(fields, "exam_tag", d.examTag(), true);
        field(fields, "sort_order", d.sortOrder(), true);
        field(fields, "retired", d.retired(), false);
        return "{\n  \"deck\": {\n    " + String.join(",\n    ", fields) + "\n  },\n  \"source\": "
                + JSON.writeValueAsString(file.source()) + ",\n  \"words\": [\n    "
                + file.words().stream().map(ContentTool::json).collect(Collectors.joining(",\n    "))
                + "\n  ]\n}\n";
    }

    private static String json(Word w) {
        List<String> fields = new ArrayList<>();
        field(fields, "uid", w.uid(), true);
        field(fields, "term", w.term(), true);
        field(fields, "reading", w.reading(), false);
        field(fields, "pos", w.pos(), false);
        field(fields, "meaning", w.meaning(), true);
        field(fields, "example", w.example(), false);
        field(fields, "example_translation", w.exampleTranslation(), false);
        return "{" + String.join(", ", fields) + "}";
    }

    private static void field(List<String> fields, String name, Object value, boolean always) {
        if (always || value != null) {
            fields.add("\"" + name + "\": " + JSON.writeValueAsString(value));
        }
    }

    private static String read(Path csv) throws IOException {
        try {
            return Files.readString(csv);
        } catch (CharacterCodingException notUtf8) {
            throw new IllegalArgumentException(
                    csv + " không phải UTF-8; trong Excel chọn lưu kiểu \"CSV UTF-8\"", notUtf8);
        }
    }

    private Path deckPath(String code) {
        return directory.resolve("decks").resolve(code + ".json");
    }

    private ContentLock oldLock() throws IOException {
        Path path = directory.resolve(LOCK);
        return Files.exists(path) ? ContentLock.parse(Files.readString(path)) : ContentLock.empty();
    }

    private void write(ContentLock lock) throws IOException {
        Files.writeString(directory.resolve(LOCK), lock.render());
        out.println("Đã ghi content.lock: " + lock.items().size() + " bộ/đề");
    }

    private static void failIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Chưa ghi gì:\n - " + String.join("\n - ", errors));
        }
    }
}

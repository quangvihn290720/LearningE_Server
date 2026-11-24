package com.learninge.server.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learninge.server.content.domain.ContentBundle;
import com.learninge.server.content.domain.ContentLock;
import com.learninge.server.content.domain.ContentValidator;
import com.learninge.server.content.domain.TermKey;
import com.learninge.server.content.service.ContentReader;
import com.learninge.server.content.tool.ContentTool;
import com.learninge.server.content.tool.DeckCsv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Luật soạn nội dung: kiểm chính content-data của repo (cổng CI) và công cụ soạn, không cần MongoDB.
 */
class ContentFilesTest {
    private static final Path REPO = Path.of(System.getProperty("content.dir"));

    @TempDir
    Path dir;

    @BeforeEach
    void copyRepositoryContent() throws IOException {
        try (Stream<Path> paths = Files.walk(REPO)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path target = dir.resolve(REPO.relativize(path).toString());
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
    }

    private String tool(String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ContentTool(dir, new PrintStream(out, true, StandardCharsets.UTF_8)).run(List.of(args));
        return out.toString(StandardCharsets.UTF_8);
    }

    private void edit(String file, String from, String to) throws IOException {
        Path path = dir.resolve(file);
        String text = Files.readString(path);
        assertThat(text).contains(from);
        Files.writeString(path, text.replace(from, to));
    }

    private void editDeck(String code, String from, String to) throws IOException {
        edit("decks/" + code + ".json", from, to);
    }

    private ContentBundle read() {
        return ContentReader.read(dir);
    }

    private ContentLock lock() throws IOException {
        return ContentLock.parse(Files.readString(dir.resolve("content.lock")));
    }

    private void fails(String message, String... args) {
        assertThatThrownBy(() -> tool(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    // --- Cổng CI ---------------------------------------------------------------------------------

    @Test
    void repositoryContentIsValidAndMatchesItsLock() throws IOException {
        assertThat(tool("check")).contains("hợp lệ");
    }

    // --- Khóa ------------------------------------------------------------------------------------

    @Test
    void lockSurvivesWritingAndReading() throws IOException {
        assertThat(ContentLock.parse(lock().render())).isEqualTo(lock());
    }

    @Test
    void editingWithoutBumpingTheVersionFails() throws IOException {
        editDeck("T05", "\"meaning\": \"quả táo\"", "\"meaning\": \"trái táo\"");
        fails("T05: nội dung đã đổi, phải tăng version lên 2", "lock");
        editDeck("T05", "\"version\": 1", "\"version\": 2");
        fails("chưa khớp", "check");
        tool("lock");
        tool("check");
        assertThat(lock().items().get("T05").version()).isEqualTo(2);
    }

    @Test
    void loweringTheVersionFails() throws IOException {
        editDeck("T05", "\"version\": 1", "\"version\": 3");
        tool("lock");
        editDeck("T05", "\"version\": 3", "\"version\": 2");
        fails("version lùi", "lock");
    }

    @Test
    void deletingADeckFileFailsButRetiringWorks() throws IOException {
        Path file = dir.resolve("decks/T05.json");
        String text = Files.readString(file);
        Files.delete(file);
        fails("T05: không được xóa hẳn", "lock");
        Files.writeString(file, text.replace("\"version\": 1", "\"version\": 2, \"retired\": true"));
        tool("lock");
    }

    @Test
    void uidOfARemovedWordCannotBecomeAnotherWord() throws IOException {
        tool("export", "T05", dir.resolve("a.csv").toString());
        Path csv = dir.resolve("a.csv");
        String all = Files.readString(csv);
        String removed = all.lines().filter(l -> l.startsWith("T05-0003")).findFirst().orElseThrow();
        Files.writeString(csv, all.replace(removed + "\r\n", ""));
        assertThat(tool("import", "T05", csv.toString())).contains("- T05-0003");
        assertThat(lock().words().get("T05-0003").active()).isFalse();

        // Khôi phục đúng từ cũ thì được; từ khác thì không.
        Files.writeString(csv, Files.readString(csv) + "T05-0003,zebra,,,ngựa vằn,,\r\n");
        fails("T05-0003", "import", "T05", csv.toString());
        Files.writeString(csv, all);
        assertThat(tool("import", "T05", csv.toString())).contains("+ T05-0003", "T05 version 3");
    }

    @Test
    void changingTheTermOfALiveWordIsOnlyAWarning() throws IOException {
        editDeck("T05", "\"version\": 1", "\"version\": 2");
        editDeck("T05", "\"term\": \"apple\"", "\"term\": \"green apple\"");
        assertThat(tool("lock")).contains("Xem lại", "green apple");
    }

    // --- CSV -------------------------------------------------------------------------------------

    @Test
    void everyDeckSurvivesCsvAndJsonUnchanged() throws IOException {
        for (var file : read().decks()) {
            String code = file.deck().code();
            Path csv = dir.resolve(code + ".csv");
            String before = Files.readString(dir.resolve("decks/" + code + ".json"));
            tool("export", code, csv.toString());
            assertThat(DeckCsv.parse(Files.readString(csv))).isEqualTo(file.words());
            tool("import", code, csv.toString());
            assertThat(dir.resolve("decks/" + code + ".json")).hasContent(before);
        }
        tool("check");
    }

    @Test
    void newRowsGetNeverUsedUidsAndTheVersionIsBumped() throws IOException {
        Path csv = dir.resolve("a.csv");
        tool("export", "T05", csv.toString());
        Files.writeString(csv, Files.readString(csv) + ",papaya,/pəˈpaɪə/,danh từ,\"đu đủ, quả\",,\r\n");
        assertThat(tool("import", "T05", csv.toString())).contains("+ T05-0021 papaya", "T05 version 2");
        var word = read().decks().stream().flatMap(d -> d.words().stream())
                .filter(w -> w.uid().equals("T05-0021")).findFirst().orElseThrow();
        assertThat(word.meaning()).isEqualTo("đu đủ, quả");
        assertThat(word.example()).isNull();
        tool("check");
    }

    @Test
    void aBadCsvChangesNothing() throws IOException {
        Path csv = dir.resolve("a.csv");
        Files.writeString(csv, "uid,term,meaning\n,a,b\n");
        fails("Thiếu cột", "import", "T05", csv.toString());
        Files.writeString(csv, "uid,term,reading,pos,meaning,example,example_translation\nT06-0001,a,,,b,,\n");
        fails("uid sai: T06-0001", "import", "T05", csv.toString());
        Files.writeString(csv, "uid,term,reading,pos,meaning,example,example_translation\n,a,,,b, c, d,\n");
        fails("thừa cột", "import", "T05", csv.toString());
        Files.write(csv, new byte[]{(byte) 0xE1, 'a', '\n'});
        fails("không phải UTF-8", "import", "T05", csv.toString());
        assertThat(dir.resolve("decks/T05.json")).hasSameTextualContentAs(REPO.resolve("decks/T05.json"));
        tool("check");
    }

    @ParameterizedTest
    @CsvSource({";", "'\t'"})
    void otherDelimitersAreDetected(String delimiter) {
        String csv = String.join(delimiter, "term", "uid", "reading", "pos", "meaning", "example", "example_translation")
                + "\n" + String.join(delimiter, "cat", "", "", "", "\"con \"\"mèo\"\"\"", "One, two.", "") + "\n\n";
        var words = DeckCsv.parse(csv);
        assertThat(words).hasSize(1);
        assertThat(words.getFirst().term()).isEqualTo("cat");
        assertThat(words.getFirst().meaning()).isEqualTo("con \"mèo\"");
        assertThat(words.getFirst().example()).isEqualTo("One, two.");
        assertThat(words.getFirst().uid()).isNull();
    }

    @Test
    void aNewDeckNeedsItsDetailsBeforeItCanBeLocked() throws IOException {
        Path csv = dir.resolve("a.csv");
        Files.writeString(csv, "uid,term,reading,pos,meaning,example,example_translation\n,volcano,,,núi lửa,,\n");
        tool("new", "T16", csv.toString());
        fails("T16: thiếu name", "lock");
        editDeck("T16", "\"name\": \"\"", "\"name\": \"Thiên nhiên\"");
        editDeck("T16", "\"source\": \"\"", "\"source\": \"Tự soạn\"");
        tool("lock");
        assertThat(lock().words()).containsKey("T16-0001");
        fails("chưa có", "new", "T16", csv.toString());
    }

    // --- Luật mà app dựa vào ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"résumé, en, resume", "'  Ice   Cream ', en, ice cream", "Đường, en, duong", "が, ja, が", "ｶﾞ, ja, ガ"})
    void termKeyMatchesTheSearchKeyOfTheApp(String term, String language, String key) {
        assertThat(TermKey.of(term, language)).isEqualTo(key);
    }

    @Test
    void termsThatTheAppWouldTreatAsTheSameAreRejected() throws IOException {
        editDeck("T05", "\"term\": \"apple\"", "\"term\": \"résumé\"");
        editDeck("T05", "\"term\": \"bread\"", "\"term\": \"resume\"");
        assertThat(ContentValidator.validate(read())).anyMatch(e -> e.contains("trùng từ trong bộ"));
    }

    @Test
    void examNumbersMustBeContiguous() throws IOException {
        edit("exams.json", "\"number\": 3,", "\"number\": 9,");
        assertThat(ContentValidator.validate(read())).anyMatch(e -> e.contains("phải có number 3"));
    }

    @Test
    void unknownExamTagsAndStraySpacesAreRejected() throws IOException {
        edit("exams.json", "\"exam_tag\": \"TOEIC\"", "\"exam_tag\": \"HSK\"");
        editDeck("T05", "\"term\": \"apple\"", "\"term\": \"apple \"");
        assertThat(ContentValidator.validate(read()))
                .anyMatch(e -> e.contains("exam_tag: HSK"))
                .anyMatch(e -> e.contains("khoảng trắng"));
    }
}

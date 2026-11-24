package com.learninge.server.content;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learninge.proto.app.v1.ContentWord;
import com.learninge.proto.app.v1.GetDeckRequest;
import com.learninge.proto.app.v1.GetDecksRequest;
import com.learninge.proto.app.v1.GetExamRequest;
import com.learninge.proto.app.v1.GetExamsRequest;
import com.learninge.proto.app.v1.GetManifestRequest;
import com.learninge.proto.app.v1.GetManifestResponse;
import com.learninge.proto.app.v1.ManifestItem;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.proto.internal.v1.ContentBackendGrpc.ContentBackendBlockingStub;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestMongo;
import com.learninge.server.common.testing.TestService;
import com.learninge.server.content.service.ContentImporter;
import com.mongodb.client.MongoDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TC-SRV-37..40, chạy trên chính content-data của repo.
 */
class ContentServiceTest {
    @TempDir
    Path dir;

    private final MongoDatabase database = TestMongo.newDatabase();
    private TestService service;
    private ContentImporter importer;

    @BeforeEach
    void setUp() throws IOException {
        service =
                TestService.start(
                        ContentApplication.class,
                        "content",
                        MutableClock.at("2026-11-12T00:00:00Z"),
                        TestMongo.properties(database));
        importer = service.bean(ContentImporter.class);
        copy(Path.of(System.getProperty("content.dir")), dir);
    }

    @AfterEach
    void tearDown() {
        service.close();
        database.drop();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
    }

    private void edit(String file, String from, String to) throws IOException {
        Path path = dir.resolve(file);
        String text = Files.readString(path);
        assertThat(text).contains(from);
        Files.writeString(path, text.replace(from, to));
    }

    private ContentBackendBlockingStub content() {
        return ContentBackendGrpc.newBlockingStub(service.internal());
    }

    private GetManifestResponse manifest(long ifVersion) {
        return content().getManifest(GetManifestRequest.newBuilder().setIfVersion(ifVersion).build());
    }

    @Test
    void importsTheRepositoryContentAndServesIt() {
        assertThat(importer.importFrom(dir).changed()).contains("T01", "X01", "J01", "E01");
        GetManifestResponse manifest = manifest(0);
        assertThat(manifest.getContentVersion()).isEqualTo(1);
        assertThat(manifest.getDecksList())
                .extracting(ManifestItem::getCode)
                .contains("T01", "X01", "J01");
        assertThat(manifest.getLanguagesCount()).isPositive();

        var deck = content().getDeck(GetDeckRequest.newBuilder().setCode("T01").build());
        assertThat(deck.getWordsList()).allSatisfy(w -> assertThat(w.getUid()).matches("T01-[0-9]{4}"));
        assertThat(deck.getWords(0).getTerm()).isEqualTo("mother");

        var exam = content().getExam(GetExamRequest.newBuilder().setCode("E01").build());
        assertThat(exam.getQuestionsList()).allSatisfy(q -> assertThat(q.getOptionsList()).hasSize(4));
        assertThat(exam.getQuestions(0).getWordUid()).startsWith("X01-");
        assertThat(exam.getExam().getQuestionCount()).isEqualTo(exam.getQuestionsCount());
    }

    @Test
    void servesSeveralDecksOrExamsInOneCall() {
        importer.importFrom(dir);
        var decks =
                content()
                        .getDecks(
                                GetDecksRequest.newBuilder().addAllCodes(List.of("X01", "Z99", "T01")).build())
                        .getDecksList();
        assertThat(decks).extracting(d -> d.getDeck().getCode()).containsExactly("X01", "T01");
        assertThat(decks.get(1)).isEqualTo(content().getDeck(GetDeckRequest.newBuilder().setCode("T01").build()));
        assertThat(content().getExams(GetExamsRequest.newBuilder().addCodes("E01").build()).getExams(0))
                .isEqualTo(content().getExam(GetExamRequest.newBuilder().setCode("E01").build()));
        for (List<String> bad :
                List.of(
                        List.<String>of(),
                        List.of("T01", "T01"),
                        List.of("t1"),
                        IntStream.range(10, 21).mapToObj(i -> "T" + i).toList())) {
            assertFails(
                    () -> content().getDecks(GetDecksRequest.newBuilder().addAllCodes(bad).build()),
                    ErrorCode.VALIDATION_ERROR);
        }
    }

    @Test
    void codesMayHaveThreeDigits() throws IOException {
        for (String file : List.of("decks/T12.json", "exams.json")) {
            Path path = dir.resolve(file);
            Files.writeString(path, Files.readString(path).replace("T12", "T112").replace("E01", "E101"));
        }
        assertThat(importer.importFrom(dir).changed()).contains("T112", "E101");
        assertThat(
                content().getDeck(GetDeckRequest.newBuilder().setCode("T112").build()).getWords(0).getUid())
                .isEqualTo("T112-0001");
    }

    @Test
    void importingAgainChangesNothing() {
        importer.importFrom(dir);
        var second = importer.importFrom(dir);
        assertThat(second.changed()).isEmpty();
        assertThat(second.contentVersion()).isEqualTo(1);
        assertThat(manifest(1).getNotModified()).isTrue();
        assertThat(manifest(1).getDecksCount()).isZero();
    }

    @Test
    void newDeckVersionReplacesContentAndHidesRemovedWords() throws IOException {
        importer.importFrom(dir);
        edit("decks/T01.json", "\"version\": 1", "\"version\": 2");
        edit("decks/T01.json", "\"term\": \"mother\"", "\"term\": \"mom\"");
        edit("decks/T01.json", "T01-0002", "T01-0099");
        assertThat(importer.importFrom(dir).changed()).containsExactly("T01");
        assertThat(manifest(1).getContentVersion()).isEqualTo(2);
        var words =
                content().getDeck(GetDeckRequest.newBuilder().setCode("T01").build()).getWordsList();
        assertThat(words)
                .filteredOn(w -> w.getUid().equals("T01-0002"))
                .allMatch(ContentWord::getDeleted);
        assertThat(words)
                .filteredOn(w -> w.getUid().equals("T01-0001"))
                .extracting(ContentWord::getTerm)
                .containsExactly("mom");
    }

    @Test
    void changingContentWithoutBumpingTheVersionIsAnError() throws IOException {
        importer.importFrom(dir);
        edit("decks/T01.json", "\"term\": \"mother\"", "\"term\": \"mom\"");
        assertThatThrownBy(() -> importer.importFrom(dir)).hasMessageContaining("version chưa tăng");
    }

    @Test
    void retiredItemsStayServedSoTheAppCanHideThem() throws IOException {
        importer.importFrom(dir);
        edit("decks/T01.json", "\"version\": 1", "\"version\": 2, \"retired\": true");
        edit("exams.json", "\"version\": 1", "\"version\": 2, \"retired\": true");
        assertThat(importer.importFrom(dir).changed()).containsExactly("T01", "E01");
        assertThat(importer.importFrom(dir).changed()).isEmpty();

        var deck = content().getDeck(GetDeckRequest.newBuilder().setCode("T01").build());
        assertThat(deck.getDeck().getRetired()).isTrue();
        // App cũ coi bộ không có từ là trả lời hỏng, nên từ vẫn phải có.
        assertThat(deck.getWordsList()).noneMatch(ContentWord::getDeleted);
        var exam = content().getExam(GetExamRequest.newBuilder().setCode("E01").build());
        assertThat(exam.getExam().getRetired()).isTrue();

        edit("decks/T01.json", "\"version\": 2, \"retired\": true", "\"version\": 3");
        importer.importFrom(dir);
        assertThat(content().getDeck(GetDeckRequest.newBuilder().setCode("T01").build()).getDeck().getRetired())
                .isFalse();
    }

    @Test
    void aDeckThatDisappearedFromTheFilesIsAnError() throws IOException {
        importer.importFrom(dir);
        Files.delete(dir.resolve("decks/J01.json"));
        assertThatThrownBy(() -> importer.importFrom(dir)).hasMessageContaining("J01: đã từng nạp");
    }

    @Test
    void invalidContentIsRejectedBeforeAnythingIsWritten() throws IOException {
        edit("decks/X01.json", "X01-0002", "X01-0001");
        edit("exams.json", "\"correct_index\": 0", "\"correct_index\": 7");
        assertThatThrownBy(() -> importer.importFrom(dir))
                .hasMessageContaining("trùng uid X01-0001")
                .hasMessageContaining("correct_index sai");
        assertThat(database.getCollection("decks").countDocuments()).isZero();
    }

    @Test
    void unknownFieldsAreRejected() throws IOException {
        edit("decks/T01.json", "\"version\": 1", "\"version\": 1, \"oops\": true");
        assertThatThrownBy(() -> importer.importFrom(dir)).hasMessageContaining("oops");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../x", "t01", "T1", "T0001", "{\"$ne\":\"\"}", "T01\n"})
    void badCodesAreInvalid(String code) {
        assertFails(
                () -> content().getDeck(GetDeckRequest.newBuilder().setCode(code).build()),
                ErrorCode.VALIDATION_ERROR);
        assertFails(
                () -> content().getExam(GetExamRequest.newBuilder().setCode(code).build()),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void missingContentIsNotFoundAndAnEmptyStoreIsFine() {
        assertFails(
                () -> content().getDeck(GetDeckRequest.newBuilder().setCode("Z99").build()),
                ErrorCode.NOT_FOUND);
        assertFails(
                () -> content().getExam(GetExamRequest.newBuilder().setCode("Z99").build()),
                ErrorCode.NOT_FOUND);
        assertThat(manifest(0).getContentVersion()).isZero();
        assertThat(manifest(0).getNotModified()).isFalse();
    }

    @Test
    void mongoDownMeansUnavailable() {
        try (TestService down =
                     TestService.start(
                             ContentApplication.class,
                             "content",
                             MutableClock.at("2026-11-12T00:00:00Z"),
                             TestMongo.unreachable())) {
            assertFails(
                    () ->
                            ContentBackendGrpc.newBlockingStub(down.internal())
                                    .getManifest(GetManifestRequest.getDefaultInstance()),
                    ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

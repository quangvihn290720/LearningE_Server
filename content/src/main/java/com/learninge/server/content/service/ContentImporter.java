package com.learninge.server.content.service;

import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.content.domain.ContentBundle;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Exam;
import com.learninge.server.content.domain.ContentValidator;
import com.learninge.server.content.store.ContentStore;
import com.mongodb.client.MongoDatabase;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.bson.Document;

/**
 * Nạp nội dung: kiểm hết → so phiên bản hết → mới ghi. Mục có {@code version} lớn hơn bản trong kho
 * thì thay; đổi nội dung mà quên tăng version là lỗi. Chạy lại không đổi gì.
 */
public final class ContentImporter {
    /**
     * Kết quả để in ra cho người vận hành.
     */
    public record Result(long contentVersion, List<String> changed) {
    }

    private final ContentStore store;
    private final MongoSchema schema;
    private final MongoDatabase database;
    private final Clock clock;

    public ContentImporter(
            ContentStore store, MongoSchema schema, MongoDatabase database, Clock clock) {
        this.store = store;
        this.schema = schema;
        this.database = database;
        this.clock = clock;
    }

    public Result importFrom(Path directory) {
        ContentBundle bundle = ContentReader.read(directory);
        failIfAny(ContentValidator.validate(bundle));

        schema.apply(database);
        List<String> problems = new ArrayList<>();
        List<DeckFile> newDecks =
                bundle.decks().stream().filter(d -> needsImport(d, problems)).toList();
        List<Exam> newExams =
                bundle.exams().stream().filter(e -> needsImport(bundle, e, problems)).toList();
        Set<String> present = new HashSet<>();
        bundle.decks().forEach(d -> present.add(d.deck().code()));
        bundle.exams().forEach(e -> present.add(e.code()));
        Stream.concat(store.decks().stream(), store.examHeaders().stream())
                .map(d -> d.getString("_id"))
                .filter(code -> !present.contains(code))
                .forEach(
                        code ->
                                problems.add(
                                        code
                                                + ": đã từng nạp nhưng không còn trong file; muốn ẩn thì giữ lại và"
                                                + " đặt \"retired\": true"));
        failIfAny(problems);

        Instant now = clock.instant();
        newDecks.forEach(d -> store.saveDeck(d, now));
        newExams.forEach(e -> store.saveExam(bundle, e, now));

        List<String> changed = new ArrayList<>();
        newDecks.forEach(d -> changed.add(d.deck().code()));
        newExams.forEach(e -> changed.add(e.code()));
        Optional<Document> manifest = store.manifest();
        long version = manifest.map(m -> m.getLong("content_version")).orElse(0L);
        boolean languagesChanged =
                !manifest
                        .map(m -> m.getList("languages", Document.class))
                        .equals(Optional.of(ContentStore.languageDocuments(bundle.languages())));
        if (!changed.isEmpty() || languagesChanged) {
            version++;
            store.saveManifest(version, bundle.languages(), now);
        }
        return new Result(version, changed);
    }

    private boolean needsImport(DeckFile file, List<String> problems) {
        String code = file.deck().code();
        Optional<Document> stored = store.deck(code);
        if (stored.isEmpty() || file.deck().version() > stored.get().getInteger("version")) {
            return true;
        }
        Document expected = withoutTimestamp(ContentStore.deckDocument(file, Instant.EPOCH));
        List<Document> activeWords =
                store.words(code).stream().filter(w -> !w.getBoolean("deleted")).toList();
        boolean same =
                withoutTimestamp(stored.get()).equals(expected)
                        && activeWords.equals(ContentStore.wordDocuments(file));
        if (!same) {
            problems.add("bộ " + code + ": nội dung đã đổi nhưng version chưa tăng");
        }
        return false;
    }

    private boolean needsImport(ContentBundle bundle, Exam exam, List<String> problems) {
        Optional<Document> stored = store.exam(exam.code());
        if (stored.isEmpty() || exam.version() > stored.get().getInteger("version")) {
            return true;
        }
        Document expected = withoutTimestamp(ContentStore.examDocument(bundle, exam, Instant.EPOCH));
        if (!withoutTimestamp(stored.get()).equals(expected)) {
            problems.add("đề " + exam.code() + ": nội dung đã đổi nhưng version chưa tăng");
        }
        return false;
    }

    private static Document withoutTimestamp(Document document) {
        Document copy = new Document(document);
        copy.remove("updated_at");
        return copy;
    }

    private static void failIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nội dung không hợp lệ, chưa ghi gì:\n - " + String.join("\n - ", errors));
        }
    }
}

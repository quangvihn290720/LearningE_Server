package com.learninge.server.content.store;

import com.learninge.server.content.domain.ContentBundle;
import com.learninge.server.content.domain.ContentBundle.Deck;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.Exam;
import com.learninge.server.content.domain.ContentBundle.Language;
import com.learninge.server.content.domain.ContentBundle.Word;
import com.learninge.server.content.domain.ContentValidator;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;

/**
 * Kho nội dung. Filter luôn dựng từ giá trị có kiểu, không bao giờ từ JSON của client (TM-20).
 */
public final class ContentStore {
    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private static final String GLOBAL = "global";

    private final MongoCollection<Document> manifest;
    private final MongoCollection<Document> decks;
    private final MongoCollection<Document> words;
    private final MongoCollection<Document> exams;

    public ContentStore(MongoDatabase database) {
        this.manifest = database.getCollection("manifest");
        this.decks = database.getCollection("decks");
        this.words = database.getCollection("words");
        this.exams = database.getCollection("exams");
    }

    // --- Đọc -------------------------------------------------------------------------------------

    public Optional<Document> manifest() {
        return Optional.ofNullable(manifest.find(Filters.eq("_id", GLOBAL)).first());
    }

    public List<Document> decks() {
        return decks.find().sort(Sorts.ascending("sort_order", "_id")).into(new ArrayList<>());
    }

    public List<Document> examHeaders() {
        return exams
                .find()
                .projection(Projections.exclude("questions"))
                .sort(Sorts.ascending("sort_order", "_id"))
                .into(new ArrayList<>());
    }

    public Optional<Document> deck(String code) {
        return Optional.ofNullable(decks.find(Filters.eq("_id", code)).first());
    }

    public Optional<Document> exam(String code) {
        return Optional.ofNullable(exams.find(Filters.eq("_id", code)).first());
    }

    /**
     * Gồm cả từ đã gỡ ({@code deleted}) để app ẩn chúng.
     */
    public List<Document> words(String deckCode) {
        return words
                .find(Filters.eq("deck_code", deckCode))
                .sort(Sorts.ascending("sort_order", "_id"))
                .into(new ArrayList<>());
    }

    // --- Ghi (chỉ lệnh import) --------------------------------------------------------------------

    /**
     * Ghi từ trước, bộ sau: lỗi giữa chừng thì phiên bản bộ chưa đổi và chạy lại là đủ.
     */
    public void saveDeck(DeckFile file, Instant now) {
        String code = file.deck().code();
        List<Document> documents = wordDocuments(file);
        List<String> uids = documents.stream().map(d -> d.getString("_id")).toList();
        for (Document document : documents) {
            words.replaceOne(Filters.eq("_id", document.getString("_id")), document, UPSERT);
        }
        words.updateMany(
                Filters.and(Filters.eq("deck_code", code), Filters.nin("_id", uids)),
                Updates.set("deleted", true));
        decks.replaceOne(Filters.eq("_id", code), deckDocument(file, now), UPSERT);
    }

    public void saveExam(ContentBundle bundle, Exam exam, Instant now) {
        exams.replaceOne(Filters.eq("_id", exam.code()), examDocument(bundle, exam, now), UPSERT);
    }

    /**
     * Ghi cuối cùng: app chỉ thấy nội dung mới sau khi manifest đổi.
     */
    public void saveManifest(long version, List<Language> languages, Instant now) {
        manifest.replaceOne(
                Filters.eq("_id", GLOBAL),
                new Document("_id", GLOBAL)
                        .append("content_version", version)
                        .append("languages", languageDocuments(languages))
                        .append("updated_at", Date.from(now)),
                UPSERT);
    }

    // --- Ánh xạ ----------------------------------------------------------------------------------

    public static List<Document> languageDocuments(List<Language> languages) {
        return languages.stream()
                .map(
                        l ->
                                new Document("code", l.code())
                                        .append("name_vi", l.nameVi())
                                        .append("tts_locale", l.ttsLocale())
                                        .append("reading_label", l.readingLabel())
                                        .append("sort_order", l.sortOrder()))
                .toList();
    }

    public static Document deckDocument(DeckFile file, Instant now) {
        Deck deck = file.deck();
        Document document = new Document("_id", deck.code())
                .append("name", deck.name())
                .append("description", deck.description())
                .append("language_code", deck.language())
                .append("level", deck.level())
                .append("icon", deck.icon())
                .append("exam_tag", deck.examTag())
                .append("sort_order", deck.sortOrder())
                .append("version", deck.version())
                .append("word_count", file.words().size())
                .append("updated_at", Date.from(now));
        return retired(document, deck.isRetired());
    }

    public static List<Document> wordDocuments(DeckFile file) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < file.words().size(); i++) {
            Word word = file.words().get(i);
            documents.add(
                    new Document("_id", word.uid())
                            .append("deck_code", file.deck().code())
                            .append("term", word.term())
                            .append("reading", word.reading())
                            .append("part_of_speech", word.pos())
                            .append("meaning_vi", word.meaning())
                            .append("example", word.example())
                            .append("example_translation", word.exampleTranslation())
                            .append("sort_order", i + 1)
                            .append("deleted", false));
        }
        return documents;
    }

    public static Document examDocument(ContentBundle bundle, Exam exam, Instant now) {
        List<Document> questions =
                exam.questions().stream()
                        .map(
                                q ->
                                        new Document("number", q.number())
                                                .append("passage", q.passage())
                                                .append("question", q.question())
                                                .append("options", q.options())
                                                .append("correct_index", q.correctIndex())
                                                .append("explanation", q.explanation())
                                                .append("word_uid", ContentValidator.wordUid(bundle, q).orElse(null)))
                        .toList();
        Document document = new Document("_id", exam.code())
                .append("exam_tag", exam.examTag())
                .append("title", exam.title())
                .append("part_label", exam.partLabel())
                .append("description", exam.description())
                .append("question_count", questions.size())
                .append("duration_sec", exam.durationSec())
                .append("sort_order", exam.sortOrder())
                .append("version", exam.version())
                .append("updated_at", Date.from(now))
                .append("questions", questions);
        return retired(document, exam.isRetired());
    }

    /**
     * Chỉ ghi khi đã ngừng dùng, để tài liệu nạp từ trước vẫn so khớp với file.
     */
    private static Document retired(Document document, boolean retired) {
        return retired ? document.append("retired", true) : document;
    }
}

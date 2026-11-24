package com.learninge.server.content.grpc;

import com.learninge.proto.app.v1.ContentDeck;
import com.learninge.proto.app.v1.ContentExam;
import com.learninge.proto.app.v1.ContentWord;
import com.learninge.proto.app.v1.ExamQuestion;
import com.learninge.proto.app.v1.GetDeckRequest;
import com.learninge.proto.app.v1.GetDeckResponse;
import com.learninge.proto.app.v1.GetDecksRequest;
import com.learninge.proto.app.v1.GetDecksResponse;
import com.learninge.proto.app.v1.GetExamRequest;
import com.learninge.proto.app.v1.GetExamResponse;
import com.learninge.proto.app.v1.GetExamsRequest;
import com.learninge.proto.app.v1.GetExamsResponse;
import com.learninge.proto.app.v1.GetManifestRequest;
import com.learninge.proto.app.v1.GetManifestResponse;
import com.learninge.proto.app.v1.Language;
import com.learninge.proto.app.v1.ManifestItem;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.content.store.ContentStore;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.bson.Document;

/**
 * EP-14..EP-16.
 */
public final class ContentBackendService extends ContentBackendGrpc.ContentBackendImplBase {
    private static final Pattern CODE = Pattern.compile("[A-Z][0-9]{2,3}");

    private static final int MAX_CODES = 10;

    private final ContentStore store;

    public ContentBackendService(ContentStore store) {
        this.store = store;
    }

    @Override
    public void getManifest(
            GetManifestRequest request, StreamObserver<GetManifestResponse> observer) {
        Optional<Document> manifest = store.manifest();
        long version = manifest.map(m -> m.getLong("content_version")).orElse(0L);
        GetManifestResponse.Builder response =
                GetManifestResponse.newBuilder().setContentVersion(version);
        if (version > 0 && request.getIfVersion() == version) {
            Unary.reply(observer, response.setNotModified(true).build());
            return;
        }
        manifest
                .map(m -> m.getList("languages", Document.class))
                .orElse(List.of())
                .forEach(l -> response.addLanguages(language(l)));
        store.decks().forEach(d -> response.addDecks(item(d, "word_count")));
        store.examHeaders().forEach(e -> response.addExams(item(e, "question_count")));
        Unary.reply(observer, response.build());
    }

    @Override
    public void getDeck(GetDeckRequest request, StreamObserver<GetDeckResponse> observer) {
        String code = requireCode(request.getCode());
        Unary.reply(observer, findDeck(code).orElseThrow(() -> AppException.of(ErrorCode.NOT_FOUND)));
    }

    @Override
    public void getDecks(GetDecksRequest request, StreamObserver<GetDecksResponse> observer) {
        GetDecksResponse.Builder response = GetDecksResponse.newBuilder();
        requireCodes(request.getCodesList()).forEach(c -> findDeck(c).ifPresent(response::addDecks));
        Unary.reply(observer, response.build());
    }

    @Override
    public void getExams(GetExamsRequest request, StreamObserver<GetExamsResponse> observer) {
        GetExamsResponse.Builder response = GetExamsResponse.newBuilder();
        requireCodes(request.getCodesList()).forEach(c -> findExam(c).ifPresent(response::addExams));
        Unary.reply(observer, response.build());
    }

    private Optional<GetDeckResponse> findDeck(String code) {
        return store
                .deck(code)
                .map(
                        d -> {
                            GetDeckResponse.Builder response = GetDeckResponse.newBuilder().setDeck(deck(d));
                            store.words(code).forEach(w -> response.addWords(word(w)));
                            return response.build();
                        });
    }

    private Optional<GetExamResponse> findExam(String code) {
        return store
                .exam(code)
                .map(
                        d -> {
                            GetExamResponse.Builder response = GetExamResponse.newBuilder().setExam(exam(d));
                            d.getList("questions", Document.class).forEach(q -> response.addQuestions(question(q)));
                            return response.build();
                        });
    }

    @Override
    public void getExam(GetExamRequest request, StreamObserver<GetExamResponse> observer) {
        String code = requireCode(request.getCode());
        Unary.reply(observer, findExam(code).orElseThrow(() -> AppException.of(ErrorCode.NOT_FOUND)));
    }

    /**
     * Bộ 1.000 từ khoảng 200 KB (NFR-N7) nên mười bộ vẫn dưới trần 4 MiB của một phản hồi.
     */
    private static List<String> requireCodes(List<String> codes) {
        if (codes.isEmpty() || codes.size() > MAX_CODES || Set.copyOf(codes).size() != codes.size()) {
            throw AppException.invalid("codes", FieldErrors.OUT_OF_RANGE);
        }
        codes.forEach(ContentBackendService::requireCode);
        return codes;
    }

    private static String requireCode(String code) {
        if (!CODE.matcher(code).matches()) {
            throw AppException.invalid("code", FieldErrors.INVALID_FORMAT);
        }
        return code;
    }

    private static Language language(Document d) {
        return Language.newBuilder()
                .setCode(d.getString("code"))
                .setNameVi(d.getString("name_vi"))
                .setTtsLocale(d.getString("tts_locale"))
                .setReadingLabel(d.getString("reading_label"))
                .setSortOrder(d.getInteger("sort_order"))
                .build();
    }

    private static ManifestItem item(Document d, String countField) {
        return ManifestItem.newBuilder()
                .setCode(d.getString("_id"))
                .setVersion(d.getInteger("version"))
                .setItemCount(d.getInteger(countField))
                .setUpdatedAtMs(d.getDate("updated_at").getTime())
                .build();
    }

    private static ContentDeck deck(Document d) {
        ContentDeck.Builder deck =
                ContentDeck.newBuilder()
                        .setCode(d.getString("_id"))
                        .setName(d.getString("name"))
                        .setLanguageCode(d.getString("language_code"))
                        .setSortOrder(d.getInteger("sort_order"))
                        .setVersion(d.getInteger("version"))
                        .setRetired(d.getBoolean("retired", false));
        optional(d, "description", deck::setDescription);
        optional(d, "level", deck::setLevel);
        optional(d, "icon", deck::setIcon);
        optional(d, "exam_tag", deck::setExamTag);
        return deck.build();
    }

    private static ContentWord word(Document d) {
        ContentWord.Builder word =
                ContentWord.newBuilder()
                        .setUid(d.getString("_id"))
                        .setTerm(d.getString("term"))
                        .setMeaningVi(d.getString("meaning_vi"))
                        .setSortOrder(d.getInteger("sort_order"))
                        .setDeleted(d.getBoolean("deleted"));
        optional(d, "reading", word::setReading);
        optional(d, "part_of_speech", word::setPartOfSpeech);
        optional(d, "example", word::setExample);
        optional(d, "example_translation", word::setExampleTranslation);
        return word.build();
    }

    private static ContentExam exam(Document d) {
        ContentExam.Builder exam =
                ContentExam.newBuilder()
                        .setCode(d.getString("_id"))
                        .setTitle(d.getString("title"))
                        .setQuestionCount(d.getInteger("question_count"))
                        .setDurationSec(d.getInteger("duration_sec"))
                        .setSortOrder(d.getInteger("sort_order"))
                        .setVersion(d.getInteger("version"))
                        .setRetired(d.getBoolean("retired", false));
        optional(d, "exam_tag", exam::setExamTag);
        optional(d, "part_label", exam::setPartLabel);
        optional(d, "description", exam::setDescription);
        return exam.build();
    }

    private static ExamQuestion question(Document d) {
        ExamQuestion.Builder question =
                ExamQuestion.newBuilder()
                        .setNumber(d.getInteger("number"))
                        .setQuestion(d.getString("question"))
                        .addAllOptions(d.getList("options", String.class))
                        .setCorrectIndex(d.getInteger("correct_index"));
        optional(d, "passage", question::setPassage);
        optional(d, "explanation", question::setExplanation);
        optional(d, "word_uid", question::setWordUid);
        return question.build();
    }

    private static void optional(Document d, String field, Consumer<String> setter) {
        String value = d.getString(field);
        if (value != null) {
            setter.accept(value);
        }
    }
}

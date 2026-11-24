package com.learninge.server.sync;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.learninge.proto.app.v1.ActivityType;
import com.learninge.proto.app.v1.CardProgressData;
import com.learninge.proto.app.v1.CefrLevel;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.ChangeResult;
import com.learninge.proto.app.v1.ChangeStatus;
import com.learninge.proto.app.v1.DeckData;
import com.learninge.proto.app.v1.ExamAttemptData;
import com.learninge.proto.app.v1.FavoriteData;
import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PullResponse;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.PushResponse;
import com.learninge.proto.app.v1.QuizAttemptData;
import com.learninge.proto.app.v1.QuizSourceType;
import com.learninge.proto.app.v1.SettingsData;
import com.learninge.proto.app.v1.StudyLogData;
import com.learninge.proto.app.v1.WordData;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;
import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.ExportUserStatsRequest;
import com.learninge.proto.internal.v1.ExportXpEventsRequest;
import com.learninge.proto.internal.v1.GetStreakRequest;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.proto.internal.v1.UserRegistered;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.id.UuidV5;
import com.learninge.server.common.ops.ServiceLauncher;
import com.learninge.server.common.sqlite.SqliteBackups;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.sqlite.SqliteRestore;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * TC-SRV-15..30: dịch vụ sync thật, SQLite thật, gọi qua gRPC.
 */
class SyncServiceTest {
    private static final String A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String DEVICE = "dddddddd-0000-4000-8000-000000000003";

    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at("2026-11-11T03:00:00Z");
    private TestService service;
    private SqliteDatabase db;

    @BeforeEach
    void setUp() {
        service =
                TestService.start(
                        SyncApplication.class,
                        "sync",
                        clock,
                        "--learninge.sync.db-path=" + dir.resolve("sync.db"),
                        "--learninge.background-enabled=false");
        db = service.bean(SqliteDatabase.class);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    // --- Trợ giúp ---------------------------------------------------------------------------------

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private Change.Builder change(String id) {
        return Change.newBuilder().setRecordId(id).setUpdatedAtMs(clock.millis());
    }

    private Change.Builder deck(String id) {
        return change(id)
                .setDeck(
                        DeckData.newBuilder()
                                .setName("TOEIC")
                                .setLanguageCode("en")
                                .setCreatedAtMs(clock.millis()));
    }

    private Change.Builder word(String id, String deckId) {
        return change(id)
                .setWord(
                        WordData.newBuilder()
                                .setDeckUuid(deckId)
                                .setTerm("apple")
                                .setMeaningVi("quả táo")
                                .setCreatedAtMs(clock.millis()));
    }

    private Change.Builder card(String wordUid, int box, long updatedAt) {
        return Change.newBuilder()
                .setRecordId(wordUid)
                .setUpdatedAtMs(updatedAt)
                .setCardProgress(
                        CardProgressData.newBuilder()
                                .setBox(box)
                                .setNextReviewDate("2026-11-13")
                                .setFirstLearnedDate("2026-11-10")
                                .setReviewCount(2));
    }

    private Change.Builder study(String id, String date) {
        return change(id)
                .setStudyLog(
                        StudyLogData.newBuilder()
                                .setActivityType(ActivityType.ACTIVITY_TYPE_REVIEW)
                                .setStudyDate(date)
                                .setCreatedAtMs(clock.millis()));
    }

    private Change.Builder xp(String id, XpSourceType type, int correct, int wrong) {
        return change(id)
                .setXpEvent(
                        XpEventData.newBuilder()
                                .setSourceType(type)
                                .setCorrectCount(correct)
                                .setWrongCount(wrong)
                                .setTotalCount(correct + wrong)
                                .setOccurredAtMs(clock.millis()));
    }

    private Change.Builder cardXp(String id) {
        return xp(id, XpSourceType.XP_SOURCE_TYPE_CARD_SESSION, 50, 0); // 100 XP
    }

    private PushResponse push(String user, Change.Builder... changes) {
        return push(user, Arrays.stream(changes).map(Change.Builder::build).toList());
    }

    private PushResponse push(String user, List<Change> changes) {
        return SyncBackendGrpc.newBlockingStub(service.as(user))
                .push(PushRequest.newBuilder().setDeviceId(DEVICE).addAllChanges(changes).build());
    }

    private ChangeResult pushOne(String user, Change.Builder change) {
        return push(user, change).getResults(0);
    }

    private void assertRejected(String user, Change.Builder change, String code) {
        ChangeResult result = pushOne(user, change);
        assertThat(result.getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_REJECTED);
        assertThat(result.getError().getCode()).isEqualTo(code);
        assertThat(result.hasCurrent()).isFalse();
    }

    private PullResponse pull(String user, long since, int limit) {
        return SyncBackendGrpc.newBlockingStub(service.as(user))
                .pull(PullRequest.newBuilder().setSince(since).setLimit(limit).build());
    }

    private List<Change> pullAll(String user) {
        return pull(user, 0, 0).getChangesList();
    }

    private long count(String sql) {
        return db.reader().sql("SELECT COUNT(*) FROM " + sql).query(Long.class).single();
    }

    private void publish(Event.Builder... events) {
        PublishRequest.Builder request = PublishRequest.newBuilder();
        for (Event.Builder event : events) {
            request.addEvents(event.setEventId(uuid()));
        }
        var response = EventSinkGrpc.newBlockingStub(service.internal()).publish(request.build());
        assertThat(response.getAckedEventIdsCount()).isEqualTo(events.length);
    }

    private static Event.Builder registered(String user) {
        return Event.newBuilder()
                .setType("user.registered")
                .setSource("auth")
                .setUserRegistered(UserRegistered.newBuilder().setUserId(user));
    }

    private static Event.Builder deleted(String user) {
        return Event.newBuilder()
                .setType("user.deleted")
                .setSource("auth")
                .setUserDeleted(UserDeleted.newBuilder().setUserId(user));
    }

    private List<Event> events(String type, String destination) {
        return db
                .reader()
                .sql("SELECT payload FROM outbox WHERE type = ? AND destination = ? ORDER BY id")
                .params(type, destination)
                .query((rs, i) -> rs.getBytes(1))
                .list()
                .stream()
                .map(
                        bytes -> {
                            try {
                                return Event.parseFrom(bytes);
                            } catch (InvalidProtocolBufferException e) {
                                throw new AssertionError(e);
                            }
                        })
                .toList();
    }

    private List<Change> oneOfEach() {
        String deckId = uuid();
        return List.of(
                change("")
                        .setSettings(
                                SettingsData.newBuilder().setLevel(CefrLevel.CEFR_LEVEL_A2).setDailyGoal(15))
                        .build(),
                deck(deckId).build(),
                word(uuid(), deckId).build(),
                change("T01-0007")
                        .setFavorite(FavoriteData.newBuilder().setCreatedAtMs(clock.millis()))
                        .build(),
                card("T01-0007", 3, clock.millis()).build(),
                study(uuid(), "2026-11-11").build(),
                change(uuid())
                        .setQuizAttempt(
                                QuizAttemptData.newBuilder()
                                        .setSourceType(QuizSourceType.QUIZ_SOURCE_TYPE_DECK)
                                        .setDeckRef(deckId)
                                        .setScore(70)
                                        .setCorrectCount(7)
                                        .setTotalCount(10)
                                        .setDurationSec(60)
                                        .setCreatedAtMs(clock.millis()))
                        .build(),
                change(uuid())
                        .setExamAttempt(
                                ExamAttemptData.newBuilder()
                                        .setTestCode("E01")
                                        .setScore(80)
                                        .setCorrectCount(16)
                                        .setTotalCount(20)
                                        .setDurationSec(900)
                                        .setStartedAtMs(clock.millis() - 1000)
                                        .setSubmittedAtMs(clock.millis()))
                        .build(),
                xp(uuid(), XpSourceType.XP_SOURCE_TYPE_QUIZ, 10, 0).build());
    }

    // --- Push cơ bản ------------------------------------------------------------------------------

    @Test
    void appliesEveryRecordTypeAndPullReturnsThemUnchanged() {
        List<Change> sent = oneOfEach();
        PushResponse response = push(A, sent);

        assertThat(response.getResultsList())
                .extracting(ChangeResult::getStatus)
                .containsOnly(ChangeStatus.CHANGE_STATUS_APPLIED);
        assertThat(response.getResultsList())
                .extracting(ChangeResult::getServerSeq)
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(response.getResults(8).getCurrent().getXpEvent().getXpAwarded()).isEqualTo(15);

        List<Change> pulled = pullAll(A);
        assertThat(pulled).hasSize(9);
        for (int i = 0; i < 8; i++) {
            assertThat(pulled.get(i).toBuilder().clearServerSeq().build()).isEqualTo(sent.get(i));
        }
        assertThat(pulled.get(8).getXpEvent().getXpAwarded()).isEqualTo(15);
        assertThat(pullAll(B)).isEmpty();
    }

    @Test
    void emitsXpAndProgressEventsInTheSameTransaction() {
        push(A, oneOfEach());
        Event xp = events("xp.recorded", "gamification").getFirst();
        assertThat(xp.getXpRecorded().getXpAwarded()).isEqualTo(15);
        assertThat(xp.getXpRecorded().getWeekStart()).isEqualTo("2026-11-09");
        assertThat(events("xp.recorded", "insight").getFirst().toString()).doesNotContain(A);
        assertThat(events("progress.changed", "gamification"))
                .singleElement()
                .extracting(e -> e.getProgressChanged().getCurrentStreak())
                .isEqualTo(1);
        var insight = events("progress.changed", "insight").getFirst().getProgressChanged();
        assertThat(insight.getCards()).isEqualTo(1);
        assertThat(insight.getQuizzes()).isEqualTo(1);
        assertThat(insight.getExams()).isEqualTo(1);
        assertThat(insight.getRecords()).isEqualTo(9);
        assertThat(insight.getUserId()).isEmpty();
    }

    @Test
    void resendingABatchIsIdempotent() {
        List<Change> sent = oneOfEach();
        push(A, sent);
        long xpEvents = count("outbox WHERE type = 'xp.recorded'");
        PushResponse again = push(A, sent);
        assertThat(again.getResultsList())
                .extracting(ChangeResult::getStatus)
                .containsExactly(
                        ChangeStatus.CHANGE_STATUS_APPLIED,
                        ChangeStatus.CHANGE_STATUS_APPLIED,
                        ChangeStatus.CHANGE_STATUS_APPLIED,
                        ChangeStatus.CHANGE_STATUS_APPLIED,
                        ChangeStatus.CHANGE_STATUS_STALE,
                        ChangeStatus.CHANGE_STATUS_DUPLICATE,
                        ChangeStatus.CHANGE_STATUS_DUPLICATE,
                        ChangeStatus.CHANGE_STATUS_DUPLICATE,
                        ChangeStatus.CHANGE_STATUS_DUPLICATE);
        assertThat(again.getResults(8).getCurrent().getXpEvent().getXpAwarded()).isEqualTo(15);
        assertThat(count("outbox WHERE type = 'xp.recorded'")).isEqualTo(xpEvents);
        assertThat(pullAll(A)).hasSize(9);
        assertThat(streak(A).getTotalXp()).isEqualTo(15);
    }

    @Test
    void parallelPushesOfTheSameXpEventAwardItOnce() {
        String id = uuid();
        List<CompletableFuture<ChangeStatus>> all =
                IntStream.range(0, 8)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() -> pushOne(A, cardXp(id)).getStatus()))
                        .toList();
        List<ChangeStatus> statuses = all.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).containsOnlyOnce(ChangeStatus.CHANGE_STATUS_APPLIED);
        assertThat(streak(A).getTotalXp()).isEqualTo(100);
        assertThat(count("outbox WHERE type = 'xp.recorded' AND destination = 'gamification'"))
                .isEqualTo(1);
    }

    // --- Xung đột ---------------------------------------------------------------------------------

    @Test
    void cardProgressKeepsTheNewerEditRegardlessOfArrivalOrder() {
        long ten = clock.millis() - 7_200_000;
        long eleven = clock.millis() - 3_600_000;
        pushOne(A, card("T01-0007", 1, eleven));
        ChangeResult late = pushOne(A, card("T01-0007", 3, ten));
        assertThat(late.getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_STALE);
        assertThat(late.getCurrent().getCardProgress().getBox()).isEqualTo(1);
        assertThat(pushOne(A, card("T01-0007", 5, eleven)).getStatus())
                .isEqualTo(ChangeStatus.CHANGE_STATUS_STALE);
        assertThat(pullAll(A))
                .singleElement()
                .extracting(c -> c.getCardProgress().getBox())
                .isEqualTo(1);
    }

    @Test
    void decksUseLastArrivalWins() {
        String id = uuid();
        pushOne(A, deck(id));
        Change.Builder rename = deck(id).setUpdatedAtMs(clock.millis() - 60_000);
        rename.getDeckBuilder().setName("IELTS");
        assertThat(pushOne(A, rename).getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_APPLIED);
        assertThat(pullAll(A).getFirst().getDeck().getName()).isEqualTo("IELTS");
    }

    @Test
    void clampsTimestampsFromFastClocks() {
        long year2100 = 4_102_444_800_000L;
        pushOne(A, card("T01-0007", 5, year2100));
        assertThat(pullAll(A).getFirst().getUpdatedAtMs()).isEqualTo(clock.millis());
        clock.advance(Duration.ofSeconds(1));
        assertThat(pushOne(A, card("T01-0007", 1, clock.millis())).getStatus())
                .isEqualTo(ChangeStatus.CHANGE_STATUS_APPLIED);
    }

    @Test
    void tombstonesSurviveAndCanBeEmpty() {
        String deckId = uuid();
        String wordId = uuid();
        push(A, deck(deckId), word(wordId, deckId));
        push(
                A,
                change(deckId).setDeleted(true).setDeck(DeckData.getDefaultInstance()),
                change(wordId).setDeleted(true).setWord(WordData.getDefaultInstance()));
        assertThat(pullAll(A)).hasSize(2).allMatch(Change::getDeleted);
        assertThat(pullAll(A).getFirst().getDeck().getName()).isEqualTo("TOEIC");
        assertRejected(A, word(uuid(), deckId), "DECK_NOT_OWNED");
    }

    // --- Từ chối từng bản ghi ---------------------------------------------------------------------

    @Test
    void rejectsBadRecordsWithoutFailingTheBatch() {
        PushResponse response =
                push(
                        A,
                        deck(uuid()),
                        word(uuid(), uuid()),
                        change(uuid()),
                        deck("not-a-uuid"),
                        study(uuid(), "2026-13-40"),
                        card("T01-0007", 6, clock.millis()),
                        card("../etc", 1, clock.millis()),
                        change(uuid()).setExamAttempt(ExamAttemptData.newBuilder().setTestCode("nope")),
                        study(uuid(), "2026-11-11"));
        assertThat(response.getResultsList())
                .extracting(r -> r.getError().getCode())
                .containsExactly(
                        "",
                        "DECK_NOT_OWNED",
                        "UNKNOWN_ENTITY",
                        "INVALID_RECORD",
                        "INVALID_RECORD",
                        "INVALID_RECORD",
                        "INVALID_RECORD",
                        "UNKNOWN_TEST",
                        "");
        assertThat(pullAll(A)).hasSize(2);
    }

    @Test
    void rejectsOversizedText() {
        String deckId = uuid();
        pushOne(A, deck(deckId));
        Change.Builder huge = word(uuid(), deckId);
        huge.getWordBuilder().setTerm("x".repeat(5000));
        assertRejected(A, huge, "INVALID_RECORD");
        Change.Builder edge = word(uuid(), deckId);
        edge.getWordBuilder().setTerm("ờ".repeat(50));
        assertThat(pushOne(A, edge).getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_APPLIED);
    }

    @Test
    void defaultNotebookCannotBeDeletedOrFaked() {
        String notebook = UuidV5.notebook(A);
        Change.Builder own = deck(notebook);
        assertThat(pushOne(A, own).getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_APPLIED);
        assertRejected(A, deck(notebook).setDeleted(true), "INVALID_RECORD");
        Change.Builder fake = deck(uuid());
        fake.getDeckBuilder().setIsDefault(true);
        pushOne(A, fake);
        assertThat(count("decks WHERE is_default = 1")).isEqualTo(1);
    }

    // --- IDOR -------------------------------------------------------------------------------------

    @Test
    void anotherUsersRecordsCannotBeReadOrChanged() {
        String deckId = uuid();
        String wordId = uuid();
        String logId = uuid();
        String xpId = uuid();
        push(A, deck(deckId), word(wordId, deckId), study(logId, "2026-11-11"), cardXp(xpId));
        List<Change> before = pullAll(A);

        assertRejected(B, deck(deckId), "INVALID_RECORD");
        assertRejected(B, deck(deckId).setDeleted(true), "INVALID_RECORD");
        assertRejected(B, word(wordId, deckId), "INVALID_RECORD");
        assertRejected(B, word(wordId, deckId).setDeleted(true), "INVALID_RECORD");
        assertRejected(B, word(uuid(), deckId), "DECK_NOT_OWNED");
        assertRejected(B, card(wordId, 1, clock.millis()), "INVALID_RECORD");
        assertRejected(B, study(logId, "2026-11-11"), "INVALID_RECORD");
        assertRejected(B, cardXp(xpId), "INVALID_RECORD");

        assertThat(pullAll(A)).isEqualTo(before);
        assertThat(pullAll(B)).isEmpty();
    }

    @Test
    void serverManagedFieldsFromClientsAreIgnored() {
        Change.Builder change = xp(uuid(), XpSourceType.XP_SOURCE_TYPE_EXAM, 5, 0).setServerSeq(9999);
        change.getXpEventBuilder().setXpAwarded(9999);
        ChangeResult result = pushOne(A, change);
        assertThat(result.getServerSeq()).isEqualTo(1);
        assertThat(result.getCurrent().getXpEvent().getXpAwarded()).isEqualTo(5);
    }

    // --- XP ---------------------------------------------------------------------------------------

    @Test
    void capsDailyXpAcrossEvents() {
        List<Integer> awarded = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            awarded.add(pushOne(A, cardXp(uuid())).getCurrent().getXpEvent().getXpAwarded());
        }
        assertThat(awarded).containsExactly(100, 100, 100, 100, 100, 0);
        clock.advance(Duration.ofDays(1));
        assertThat(pushOne(A, cardXp(uuid())).getCurrent().getXpEvent().getXpAwarded()).isEqualTo(100);
        assertThat(streak(A).getTotalXp()).isEqualTo(600);
    }

    @Test
    void outOfRangeCountsAreRejectedButAbsurdOnesAreStoredWithZeroXp() {
        assertRejected(A, xp(uuid(), XpSourceType.XP_SOURCE_TYPE_QUIZ, 201, 0), "INVALID_RECORD");
        assertRejected(A, xp(uuid(), XpSourceType.XP_SOURCE_TYPE_QUIZ, -1, 2), "INVALID_RECORD");
        Change.Builder mismatch = xp(uuid(), XpSourceType.XP_SOURCE_TYPE_QUIZ, 5, 2);
        mismatch.getXpEventBuilder().setTotalCount(3);
        ChangeResult result = pushOne(A, mismatch);
        assertThat(result.getStatus()).isEqualTo(ChangeStatus.CHANGE_STATUS_APPLIED);
        assertThat(result.getCurrent().getXpEvent().getXpAwarded()).isZero();

        Change.Builder old = cardXp(uuid());
        old.getXpEventBuilder().setOccurredAtMs(clock.millis() - Duration.ofDays(8).toMillis());
        assertThat(pushOne(A, old).getCurrent().getXpEvent().getXpAwarded()).isZero();
    }

    // --- Chuỗi ngày -------------------------------------------------------------------------------

    private com.learninge.proto.internal.v1.GetStreakResponse streak(String user) {
        return SyncBackendGrpc.newBlockingStub(service.as(user))
                .getStreak(GetStreakRequest.getDefaultInstance());
    }

    @Test
    void mergesStudyDaysFromAllDevices() {
        push(A, study(uuid(), "2026-11-09"));
        push(A, study(uuid(), "2026-11-08"), study(uuid(), "2026-11-08"));
        assertThat(streak(A).getCurrentStreak()).isEqualTo(2);
        assertThat(streak(A).getLastStudyDate()).isEqualTo("2026-11-09");
        push(A, study(uuid(), "2026-11-11"));
        assertThat(streak(A).getCurrentStreak()).isEqualTo(1);
        assertThat(streak(A).getLongestStreak()).isEqualTo(2);
        assertThat(streak(B).getLastStudyDate()).isEmpty();
    }

    // --- Giới hạn ---------------------------------------------------------------------------------

    @Test
    void acceptsFiveHundredRecordsAndRefusesOneMore() {
        List<Change> full =
                IntStream.range(0, 500).mapToObj(i -> study(uuid(), "2026-11-11").build()).toList();
        assertThat(push(A, full).getResultsCount()).isEqualTo(500);
        List<Change> over =
                Stream.concat(full.stream(), Stream.of(study(uuid(), "2026-11-11").build())).toList();
        assertFails(() -> push(A, over), ErrorCode.BATCH_TOO_LARGE);
        assertThat(count("study_log")).isEqualTo(500);
    }

    @Test
    void pushNeedsADeviceAndAUser() {
        assertFails(
                () -> SyncBackendGrpc.newBlockingStub(service.as(A)).push(PushRequest.getDefaultInstance()),
                ErrorCode.VALIDATION_ERROR);
        assertFails(
                () ->
                        SyncBackendGrpc.newBlockingStub(service.internal())
                                .push(PushRequest.newBuilder().setDeviceId(DEVICE).build()),
                ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void pullPagesByCursor() {
        for (int batch = 0; batch < 3; batch++) {
            int size = batch < 2 ? 500 : 230;
            push(A, IntStream.range(0, size).mapToObj(i -> study(uuid(), "2026-11-11").build()).toList());
        }
        push(B, study(uuid(), "2026-11-11"));
        PullResponse one = pull(A, 0, 0);
        PullResponse two = pull(A, one.getNextCursor(), 500);
        PullResponse three = pull(A, two.getNextCursor(), 500);
        assertThat(List.of(one.getChangesCount(), two.getChangesCount(), three.getChangesCount()))
                .containsExactly(500, 500, 230);
        assertThat(List.of(one.getHasMore(), two.getHasMore(), three.getHasMore()))
                .containsExactly(true, true, false);
        PullResponse end = pull(A, three.getNextCursor(), 1);
        assertThat(end.getChangesCount()).isZero();
        assertThat(end.getNextCursor()).isEqualTo(three.getNextCursor());
    }

    @Test
    void pullRejectsBadCursorsAndLimits() {
        assertFails(() -> pull(A, -1, 1), ErrorCode.VALIDATION_ERROR);
        assertFails(() -> pull(A, 0, 501), ErrorCode.VALIDATION_ERROR);
        assertFails(() -> pull(A, 0, -1), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void manyUsersPushInParallelWithoutLosingWrites() {
        List<CompletableFuture<Void>> all =
                IntStream.range(0, 5)
                        .mapToObj(
                                u ->
                                        CompletableFuture.runAsync(
                                                () ->
                                                        push(
                                                                "cccccccc-0000-4000-8000-00000000000" + u,
                                                                IntStream.range(0, 300)
                                                                        .mapToObj(i -> study(uuid(), "2026-11-11").build())
                                                                        .toList())))
                        .toList();
        all.forEach(CompletableFuture::join);
        assertThat(count("study_log")).isEqualTo(1500);
        assertThat(
                db.reader()
                        .sql("SELECT COUNT(DISTINCT server_seq) FROM study_log")
                        .query(Long.class)
                        .single())
                .isEqualTo(1500);
    }

    // --- Sự kiện tài khoản ------------------------------------------------------------------------

    @Test
    void registrationCreatesOneNotebook() {
        publish(registered(A));
        publish(registered(A), registered(A));
        assertThat(pullAll(A)).hasSize(1);
        Change notebook = pullAll(A).getFirst();
        assertThat(notebook.getRecordId()).isEqualTo(UuidV5.notebook(A));
        assertThat(notebook.getDeck().getName()).isEqualTo("Sổ tay của tôi");
        assertThat(notebook.getDeck().getIsDefault()).isTrue();
    }

    @Test
    void deletionPurgesEverythingAndBlocksLateData() {
        push(A, oneOfEach());
        push(B, oneOfEach());
        publish(deleted(A));
        publish(deleted(A), registered(A));

        List<String> tables =
                db.reader()
                        .sql(
                                """
                                        SELECT m.name FROM sqlite_master m JOIN pragma_table_info(m.name) c
                                        WHERE m.type = 'table' AND c.name = 'user_id' AND m.name <> 'purged_users'
                                        """)
                        .query(String.class)
                        .list();
        assertThat(tables).hasSize(10);
        for (String table : tables) {
            assertThat(count(table + " WHERE user_id = '" + A + "'")).as(table).isZero();
            assertThat(count(table + " WHERE user_id = '" + B + "'")).as(table).isPositive();
        }
        assertFails(() -> push(A, oneOfEach()), ErrorCode.ACCOUNT_DELETED);
        assertThat(pullAll(A)).isEmpty();
    }

    @Test
    void forgedEventsAreIgnoredButAcknowledged() {
        push(A, oneOfEach());
        publish(deleted(A).setSource("sync"));
        publish(Event.newBuilder().setType("xp.recorded").setSource("sync"));
        assertThat(pullAll(A)).hasSize(9);
        List<Event> tooMany =
                IntStream.range(0, 201).mapToObj(i -> deleted(A).setEventId(uuid()).build()).toList();
        assertFails(
                () ->
                        EventSinkGrpc.newBlockingStub(service.internal())
                                .publish(PublishRequest.newBuilder().addAllEvents(tooMany).build()),
                ErrorCode.VALIDATION_ERROR);
    }

    // --- Xuất cho rebuild -------------------------------------------------------------------------

    @Test
    void exportsPageThroughAllUsers() {
        for (int i = 0; i < 3; i++) {
            push(A, cardXp(uuid()));
        }
        push(B, cardXp(uuid()));
        var stub = SyncBackendGrpc.newBlockingStub(service.internal());
        var first = stub.exportXpEvents(ExportXpEventsRequest.newBuilder().setLimit(3).build());
        assertThat(first.getHasMore()).isTrue();
        var second =
                stub.exportXpEvents(
                        ExportXpEventsRequest.newBuilder()
                                .setAfterSeq(first.getNextAfterSeq())
                                .setLimit(100_000)
                                .build());
        assertThat(second.getHasMore()).isFalse();
        assertThat(second.getEventsList()).extracting(e -> e.getUserId()).containsExactly(B);
        assertThat(
                stub.exportUserStats(ExportUserStatsRequest.newBuilder().setLimit(10).build())
                        .getStatsList())
                .extracting(s -> s.getUserId())
                .containsExactly(A, B);
        TestService.assertInternalTokenRequired(
                () ->
                        SyncBackendGrpc.newBlockingStub(service.anonymous())
                                .exportXpEvents(ExportXpEventsRequest.getDefaultInstance()));
    }

    /**
     * Khôi phục từ bản cũ: số thứ tự nhảy qua mọi mốc đã cấp, nếu không máy đã kéo tới mốc cao hơn sẽ
     * không bao giờ thấy thay đổi mới.
     */
    @Test
    void restoringABackupNeverHidesNewChangesFromDevices() {
        push(A, card("T01-0001", 1, clock.millis()));
        Path backup =
                service.bean(SqliteBackups.class).backup(dir.resolve("backup"));
        push(A, card("T01-0002", 1, clock.millis()), card("T01-0003", 1, clock.millis()));
        PullResponse before = pull(A, 0, 0);
        service.close();

        String[] args = {
                "--ops=restore",
                "--from=" + backup,
                "--learninge.sync.db-path=" + dir.resolve("sync.db"),
                "--learninge.internal-token=" + TestService.INTERNAL_TOKEN
        };
        try (var ops = ServiceLauncher.start(SyncApplication.class, "sync", builder -> {
        }, args)) {
            ops.getBean(SqliteRestore.class).run(new DefaultApplicationArguments(args));
        }
        service =
                TestService.start(
                        SyncApplication.class,
                        "sync",
                        clock,
                        "--learninge.sync.db-path=" + dir.resolve("sync.db"),
                        "--learninge.background-enabled=false");
        db = service.bean(SqliteDatabase.class);

        assertThat(pull(A, 0, 0).getChangesCount()).isEqualTo(1);
        push(A, card("T01-0009", 1, clock.millis()));
        PullResponse after = pull(A, before.getNextCursor(), 0);
        assertThat(after.getChangesList()).extracting(Change::getRecordId).containsExactly("T01-0009");
        assertThat(after.getDataEpoch()).isEqualTo(before.getDataEpoch() + 1);
    }
}

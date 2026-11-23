package com.learninge.server.allinone;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.app.v1.ActivityType;
import com.learninge.proto.app.v1.AuthServiceGrpc;
import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.CardProgressData;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.ContentServiceGrpc;
import com.learninge.proto.app.v1.DeleteAccountRequest;
import com.learninge.proto.app.v1.GetDeckRequest;
import com.learninge.proto.app.v1.GetMeRequest;
import com.learninge.proto.app.v1.GetMeResponse;
import com.learninge.proto.app.v1.GetWeeklyRequest;
import com.learninge.proto.app.v1.GetWeeklyResponse;
import com.learninge.proto.app.v1.LeaderboardServiceGrpc;
import com.learninge.proto.app.v1.LoginRequest;
import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.StudyLogData;
import com.learninge.proto.app.v1.SyncServiceGrpc;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;
import com.learninge.server.common.config.Secret;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.InternalClientInterceptor;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.id.UuidV5;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.testing.TestMongo;
import com.learninge.server.common.testing.TestService;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.content.service.ContentImporter;
import com.learninge.server.gamification.service.GameUpdates;
import com.learninge.server.gamification.service.RebuildCommand;
import com.mongodb.client.MongoDatabase;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Đi hết một vòng như app thật: qua edge, qua outbox, tới MongoDB rồi quay lại.
 */
class EndToEndTest {
    @TempDir
    Path dir;

    private final MongoDatabase content = TestMongo.database("learninge_content");
    private final MongoDatabase game = TestMongo.database("learninge_game");
    private final MongoDatabase insight = TestMongo.database("learninge_insight");
    private AllInOne server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        start(AllInOne.ALL);
    }

    private void start(List<String> services) {
        server = AllInOne.start(services, true, arguments());
        channel =
                NettyChannelBuilder.forAddress("127.0.0.1", server.ports().get("edge"))
                        .usePlaintext()
                        .build();
    }

    private String[] arguments() {
        List<String> args = new ArrayList<>();
        args.add("--learninge.mongo.uri=" + TestMongo.uri());
        args.add("--learninge.auth.db-path=" + dir.resolve("auth.db"));
        args.add("--learninge.sync.db-path=" + dir.resolve("sync.db"));
        args.add("--learninge.internal-token=" + TestService.INTERNAL_TOKEN);
        return args.toArray(String[]::new);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.close();
        List.of(content, game, insight).forEach(MongoDatabase::drop);
    }

    private Channel as(String token) {
        Metadata headers = new Metadata();
        headers.put(MetadataKeys.CLIENT_VERSION, "1");
        if (token != null) {
            headers.put(MetadataKeys.AUTHORIZATION, "Bearer " + token);
        }
        return ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private AuthServiceGrpc.AuthServiceBlockingStub auth(String token) {
        return AuthServiceGrpc.newBlockingStub(as(token));
    }

    private AuthSession register(String nickname) {
        return auth(null)
                .register(
                        RegisterRequest.newBuilder()
                                .setEmail(nickname + "@learninge.test")
                                .setPassword("abc12345")
                                .setNickname(nickname)
                                .setDisplayName("Người học")
                                .setSecurityQuestionId(1)
                                .setSecurityAnswer("Hà Nội")
                                .build());
    }

    private List<Change> pull(String token) {
        return SyncServiceGrpc.newBlockingStub(as(token))
                .pull(PullRequest.getDefaultInstance())
                .getChangesList();
    }

    private void studySession(String token) {
        long now = System.currentTimeMillis();
        SyncServiceGrpc.newBlockingStub(as(token))
                .push(
                        PushRequest.newBuilder()
                                .setDeviceId(UUID.randomUUID().toString())
                                .addChanges(
                                        Change.newBuilder()
                                                .setRecordId("T01-0001")
                                                .setUpdatedAtMs(now)
                                                .setCardProgress(
                                                        CardProgressData.newBuilder()
                                                                .setBox(2)
                                                                .setNextReviewDate("2026-12-01")
                                                                .setFirstLearnedDate("2026-11-30")))
                                .addChanges(
                                        Change.newBuilder()
                                                .setRecordId(UUID.randomUUID().toString())
                                                .setUpdatedAtMs(now)
                                                .setStudyLog(
                                                        StudyLogData.newBuilder()
                                                                .setActivityType(ActivityType.ACTIVITY_TYPE_LEARN)
                                                                .setStudyDate(VnTime.date(now).toString())
                                                                .setCreatedAtMs(now)))
                                .addChanges(
                                        Change.newBuilder()
                                                .setRecordId(UUID.randomUUID().toString())
                                                .setUpdatedAtMs(now)
                                                .setXpEvent(
                                                        XpEventData.newBuilder()
                                                                .setSourceType(XpSourceType.XP_SOURCE_TYPE_CARD_SESSION)
                                                                .setCorrectCount(7)
                                                                .setWrongCount(3)
                                                                .setTotalCount(10)
                                                                .setOccurredAtMs(now)))
                                .build());
    }

    private GetWeeklyResponse leaderboard(String token) {
        return LeaderboardServiceGrpc.newBlockingStub(as(token))
                .getWeekly(GetWeeklyRequest.getDefaultInstance());
    }

    private static void eventually(Runnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(assertion::run);
    }

    @Test
    void aLearnerCanRegisterStudyCompeteAndLeaveWithoutATrace() {
        AuthSession session = register("minh01");
        String token = session.getToken();
        String userId = session.getUser().getId();

        // Bộ mặc định đi đường outbox auth → sync.
        eventually(
                () ->
                        assertThat(pull(token))
                                .extracting(Change::getRecordId)
                                .contains(UuidV5.notebook(userId)));

        // XP đi đường outbox sync → gamification.
        studySession(token);
        eventually(
                () -> {
                    GetWeeklyResponse board = leaderboard(token);
                    assertThat(board.getEntriesList()).hasSize(1);
                    assertThat(board.getEntries(0).getNickname()).isEqualTo("minh01");
                    assertThat(board.getEntries(0).getXp()).isEqualTo(17);
                    assertThat(board.getEntries(0).getStreak()).isEqualTo(1);
                });

        GetMeResponse me = auth(token).getMe(GetMeRequest.getDefaultInstance());
        assertThat(me.getStatsPartial()).isFalse();
        assertThat(me.getStats().getTotalXp()).isEqualTo(17);
        assertThat(me.getStats().getWeekXp()).isEqualTo(17);

        // Máy thứ hai thấy tiến độ ngay.
        String second =
                auth(null)
                        .login(
                                LoginRequest.newBuilder()
                                        .setEmail("minh01@learninge.test")
                                        .setPassword("abc12345")
                                        .build())
                        .getToken();
        assertThat(pull(second))
                .filteredOn(Change::hasCardProgress)
                .singleElement()
                .extracting(c -> c.getCardProgress().getBox())
                .isEqualTo(2);

        // Nội dung.
        server
                .bean("content", ContentImporter.class)
                .importFrom(Path.of(System.getProperty("content.dir")));
        assertThat(
                ContentServiceGrpc.newBlockingStub(as(token))
                        .getDeck(GetDeckRequest.newBuilder().setCode("T01").build())
                        .getWordsCount())
                .isPositive();

        // Không PII nào sang Mongo.
        assertMongoNeverMentions("minh01@learninge.test", "Người học", "abc12345", "ha noi", token);

        // Xóa tài khoản lan khắp hệ thống.
        auth(token).deleteAccount(DeleteAccountRequest.newBuilder().setPassword("abc12345").build());
        TestService.assertFails(
                () -> auth(token).getMe(GetMeRequest.getDefaultInstance()), ErrorCode.UNAUTHENTICATED);
        TestService.assertFails(() -> pull(second), ErrorCode.UNAUTHENTICATED);
        SqliteDatabase sync = server.bean("sync", SqliteDatabase.class);
        eventually(
                () -> {
                    assertThat(sync.reader().sql("SELECT COUNT(*) FROM decks").query(Long.class).single())
                            .isZero();
                    assertThat(game.getCollection("weekly_xp").countDocuments()).isZero();
                    assertThat(game.getCollection("user_stats").countDocuments()).isZero();
                    assertThat(
                            insight
                                    .getCollection("audit_events")
                                    .countDocuments(new Document("user_hmac", new Document("$exists", true))))
                            .isZero();
                });
    }

    @Test
    void coreKeepsWorkingWhileGamificationIsDownAndCatchesUpLater() {
        channel.shutdownNow();
        server.close();
        start(List.of("insight", "content", "sync", "auth", "edge"));

        // Không có dịch vụ nào nghe ở địa chỉ mặc định của gamification.
        String token = register("lan.anh").getToken();
        studySession(token);
        GetMeResponse me = auth(token).getMe(GetMeRequest.getDefaultInstance());
        assertThat(me.getStatsPartial()).isTrue();
        assertThat(me.getStats().getTotalXp()).isEqualTo(17);
        TestService.assertFails(() -> leaderboard(token), ErrorCode.SERVICE_UNAVAILABLE);

        // Bật lại đủ dịch vụ trên cùng file SQLite: outbox tự xả.
        channel.shutdownNow();
        server.close();
        start(AllInOne.ALL);
        eventually(() -> assertThat(leaderboard(token).getMe().getXp()).isEqualTo(17));
    }

    @Test
    void rebuildRestoresTheLeaderboardAfterLosingMongo() {
        String token = register("binh99").getToken();
        studySession(token);
        eventually(() -> assertThat(leaderboard(token).getEntriesList()).hasSize(1));
        GetWeeklyResponse before = leaderboard(token);

        for (String collection : List.of("xp_events", "weekly_xp", "user_stats")) {
            game.getCollection(collection).drop();
        }
        assertThat(leaderboard(token).getEntriesList()).isEmpty();

        try (InternalChannels channels =
                     new InternalChannels(
                             Map.of(
                                     "auth", "127.0.0.1:" + server.ports().get("auth"),
                                     "sync", "127.0.0.1:" + server.ports().get("sync")),
                             new InternalClientInterceptor(Secret.valueOf(TestService.INTERNAL_TOKEN)))) {
            RebuildCommand rebuild =
                    new RebuildCommand(
                            channels,
                            server.bean("gamification", GameUpdates.class),
                            server.bean("gamification", MongoSchema.class),
                            game);
            rebuild.rebuild();
            rebuild.rebuild();
        }
        assertThat(leaderboard(token).getEntriesList()).isEqualTo(before.getEntriesList());
    }

    private void assertMongoNeverMentions(String... secrets) {
        for (MongoDatabase database : List.of(content, game, insight)) {
            for (String name : database.listCollectionNames()) {
                for (Document document : database.getCollection(name).find()) {
                    assertThat(document.toJson()).as(name).doesNotContain(secrets);
                }
            }
        }
    }
}

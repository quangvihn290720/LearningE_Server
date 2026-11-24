package com.learninge.server.insight;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.internal.v1.AuthLocked;
import com.learninge.proto.internal.v1.AuthLoginFailed;
import com.learninge.proto.internal.v1.AuthScope;
import com.learninge.proto.internal.v1.DailyStats;
import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.GetDailyStatsRequest;
import com.learninge.proto.internal.v1.InsightBackendGrpc;
import com.learninge.proto.internal.v1.LogRequestsRequest;
import com.learninge.proto.internal.v1.ProgressChanged;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.RequestLog;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.proto.internal.v1.UserRegistered;
import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestMongo;
import com.learninge.server.common.testing.TestService;
import com.mongodb.client.MongoDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TC-SRV-41..45.
 */
class InsightServiceTest {
    private static final String U = "0123456789abcdef0123456789abcdef";
    private static final String V = "fedcba9876543210fedcba9876543210";

    private final MutableClock clock = MutableClock.at("2026-11-12T03:00:00Z");
    private final MongoDatabase database = TestMongo.newDatabase();
    private TestService service;

    @BeforeEach
    void setUp() {
        service =
                TestService.start(
                        InsightApplication.class, "insight", clock, TestMongo.properties(database));
        service.bean(MongoSchema.class).apply(database);
    }

    @AfterEach
    void tearDown() {
        service.close();
        database.drop();
    }

    private Event.Builder event(String type, String source) {
        return Event.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setType(type)
                .setSource(source)
                .setCreatedAtMs(clock.millis());
    }

    private Event.Builder registered(String hmac) {
        return event("user.registered", "auth")
                .setUserRegistered(UserRegistered.newBuilder().setUserHmac(hmac));
    }

    private Event.Builder progress(String hmac) {
        return event("progress.changed", "sync")
                .setProgressChanged(
                        ProgressChanged.newBuilder()
                                .setUserHmac(hmac)
                                .setDate("2026-11-12")
                                .setCards(5)
                                .setQuizzes(1)
                                .setRecords(8));
    }

    private Event.Builder xp(String hmac) {
        return event("xp.recorded", "sync")
                .setXpRecorded(
                        XpRecorded.newBuilder()
                                .setUserHmac(hmac)
                                .setSourceType("QUIZ")
                                .setXpAwarded(15)
                                .setLocalDate("2026-11-12"));
    }

    private void publish(Event.Builder... events) {
        EventSinkGrpc.newBlockingStub(service.internal())
                .publish(
                        PublishRequest.newBuilder()
                                .addAllEvents(Arrays.stream(events).map(Event.Builder::build).toList())
                                .build());
    }

    private List<DailyStats> stats(String from, String to) {
        return InsightBackendGrpc.newBlockingStub(service.internal())
                .getDailyStats(GetDailyStatsRequest.newBuilder().setFromDate(from).setToDate(to).build())
                .getDaysList();
    }

    private long count(String collection) {
        return database.getCollection(collection).countDocuments();
    }

    @Test
    void aggregatesEachEventOnceEvenWhenRedelivered() {
        Event.Builder failed =
                event("auth.login_failed", "auth")
                        .setAuthLoginFailed(AuthLoginFailed.newBuilder().setScope(AuthScope.AUTH_SCOPE_LOGIN));
        Event.Builder locked =
                event("auth.locked", "auth")
                        .setAuthLocked(
                                AuthLocked.newBuilder()
                                        .setScope(AuthScope.AUTH_SCOPE_LOGIN)
                                        .setLockLevel(1)
                                        .setLockSeconds(60));
        Event.Builder[] all = {
                registered(U), progress(U), progress(U), progress(V), xp(U), failed, locked
        };
        publish(all);
        publish(all);

        DailyStats day = stats("2026-11-12", "2026-11-12").getFirst();
        assertThat(day.getNewUsers()).isEqualTo(1);
        assertThat(day.getDau()).isEqualTo(2);
        assertThat(day.getCardsReviewed()).isEqualTo(15);
        assertThat(day.getQuizCount()).isEqualTo(3);
        assertThat(day.getPushCount()).isEqualTo(3);
        assertThat(day.getPushRecords()).isEqualTo(24);
        assertThat(day.getXpTotal()).isEqualTo(15);
        assertThat(day.getLoginFailed()).isEqualTo(1);
        assertThat(day.getLocks()).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(7);
    }

    @Test
    void rejectsEventsCarryingRawUserIds() {
        Event.Builder leaky = registered(U);
        leaky.getUserRegisteredBuilder().setUserId("aaaaaaaa-0000-4000-8000-000000000001");
        publish(leaky);
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void deletionPurgesAPersonButKeepsAnonymousTotals() {
        publish(registered(U), progress(U), progress(V));
        log(entry(U), entry(V));
        publish(event("user.deleted", "auth").setUserDeleted(UserDeleted.newBuilder().setUserHmac(U)));
        publish(progress(U), xp(U));
        log(entry(U));

        assertThat(database.getCollection("audit_events").countDocuments(new Document("user_hmac", U)))
                .isZero();
        assertThat(database.getCollection("request_logs").countDocuments(new Document("user_hmac", U)))
                .isZero();
        assertThat(
                database
                        .getCollection("analytics_daily_users")
                        .countDocuments(new Document("_id.u", U)))
                .isZero();
        assertThat(stats("2026-11-12", "2026-11-12").getFirst().getPushCount()).isEqualTo(2);
    }

    private RequestLog entry(String hmac) {
        return RequestLog.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRpc("learninge.app.v1.SyncService/Push")
                .setDurationMs(12)
                .setUserHmac(hmac)
                .setTsMs(clock.millis())
                .build();
    }

    private int log(RequestLog... logs) {
        return InsightBackendGrpc.newBlockingStub(service.internal())
                .logRequests(LogRequestsRequest.newBuilder().addAllLogs(List.of(logs)).build())
                .getAccepted();
    }

    @Test
    void acceptsAtMostOneHundredValidLogs() {
        assertThat(log(Collections.nCopies(100, entry("")).toArray(RequestLog[]::new))).isEqualTo(100);
        assertFails(
                () -> log(Collections.nCopies(101, entry("")).toArray(RequestLog[]::new)),
                ErrorCode.VALIDATION_ERROR);
        RequestLog bad = entry("").toBuilder().setRequestId("a\r\nb").build();
        assertThat(log(bad, entry(U))).isEqualTo(1);
        assertThat(log()).isZero();
    }

    @Test
    void limitsTheStatsRange() {
        assertThat(stats("2026-09-01", "2026-12-01")).isEmpty();
        assertFails(() -> stats("2026-09-01", "2026-12-02"), ErrorCode.VALIDATION_ERROR);
        assertFails(() -> stats("2026-09-02", "2026-09-01"), ErrorCode.VALIDATION_ERROR);
        assertFails(() -> stats("x", "2026-09-01"), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void everyRpcNeedsTheInternalToken() {
        TestService.assertInternalTokenRequired(
                () ->
                        InsightBackendGrpc.newBlockingStub(service.anonymous())
                                .getDailyStats(GetDailyStatsRequest.getDefaultInstance()));
    }

    @Test
    void ttlIndexesMatchTheRetentionPolicy() {
        assertThat(ttl("audit_events", "ttl_audit_90d")).isEqualTo(7_776_000);
        assertThat(ttl("request_logs", "ttl_reqlog_7d")).isEqualTo(604_800);
        assertThat(ttl("analytics_daily_users", "ttl_dau_35d")).isZero();
    }

    private long ttl(String collection, String index) {
        return database.getCollection(collection).listIndexes().into(new ArrayList<>()).stream()
                .filter(d -> index.equals(d.getString("name")))
                .map(d -> ((Number) d.get("expireAfterSeconds")).longValue())
                .findFirst()
                .orElseThrow();
    }

    @Test
    void reportSummarisesDaysAndRpcsWithoutUserData() {
        for (int i = 0; i < 20; i++) {
            database.getCollection("request_logs")
                    .insertOne(
                            new Document("request_id", "r" + i)
                                    .append("rpc", "learninge.app.v1.SyncService/Push")
                                    .append("code", i == 0 ? 14 : i == 1 ? 16 : 0)
                                    .append("duration_ms", 10 + i)
                                    .append("user_hmac", U)
                                    .append("ts", new java.util.Date()));
        }
        database.getCollection("analytics_daily")
                .insertOne(
                        new Document("_id", "2026-11-12")
                                .append("dau", 7)
                                .append("xp_total", 1234L)
                                .append("updated_at", new java.util.Date()));
        var bytes = new java.io.ByteArrayOutputStream();
        new com.learninge.server.insight.service.ReportCommand(
                service.bean(com.learninge.server.insight.store.AnalyticsStore.class),
                service.bean(com.learninge.server.insight.store.AuditStore.class),
                clock,
                new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8))
                .run(new org.springframework.boot.DefaultApplicationArguments("--days=7"));
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("<td>2026-11-12</td><td>7</td>", "<td>1234</td>")
                .contains("SyncService/Push</td><td>20</td><td>1</td><td>1</td>", "<td>29</td></tr>")
                .doesNotContain(U, "<script");
    }
}

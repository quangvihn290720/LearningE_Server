package com.learninge.server.gamification;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learninge.proto.app.v1.GetWeeklyRequest;
import com.learninge.proto.app.v1.GetWeeklyResponse;
import com.learninge.proto.app.v1.LeaderboardEntry;
import com.learninge.proto.app.v1.WeekSelector;
import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.proto.internal.v1.GetStatsRequest;
import com.learninge.proto.internal.v1.ProgressChanged;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.proto.internal.v1.UserProfileChanged;
import com.learninge.proto.internal.v1.UserRegistered;
import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestMongo;
import com.learninge.server.common.testing.TestService;
import com.learninge.server.common.time.VnTime;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-SRV-31..36: gamification thật với MongoDB thật. */
class GamificationServiceTest {
  private static final String A = "aaaaaaaa-0000-4000-8000-000000000001";
  private static final String B = "bbbbbbbb-0000-4000-8000-000000000002";
  private static final String C = "cccccccc-0000-4000-8000-000000000003";

  private final MutableClock clock = MutableClock.at("2026-11-12T03:00:00Z");
  private final MongoDatabase database = TestMongo.newDatabase();
  private TestService service;

  @BeforeEach
  void setUp() {
    service =
        TestService.start(
            GamificationApplication.class, "gamification", clock, TestMongo.properties(database));
    service.bean(MongoSchema.class).apply(database);
  }

  @AfterEach
  void tearDown() {
    service.close();
    database.drop();
  }

  private static Event.Builder event(String type, String source) {
    return Event.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setType(type)
        .setSource(source);
  }

  private static Event.Builder registered(String user, String nickname) {
    return event("user.registered", "auth")
        .setUserRegistered(
            UserRegistered.newBuilder().setUserId(user).setNickname(nickname).setCreatedAtMs(1));
  }

  private static Event.Builder renamed(String user, String nickname, long version) {
    return event("user.profile_changed", "auth")
        .setUserProfileChanged(
            UserProfileChanged.newBuilder()
                .setUserId(user)
                .setNickname(nickname)
                .setVersion(version));
  }

  private static Event.Builder deleted(String user) {
    return event("user.deleted", "auth").setUserDeleted(UserDeleted.newBuilder().setUserId(user));
  }

  private static Event.Builder streak(String user, int days, long version) {
    return event("progress.changed", "sync")
        .setProgressChanged(
            ProgressChanged.newBuilder()
                .setUserId(user)
                .setCurrentStreak(days)
                .setLongestStreak(days)
                .setVersion(version));
  }

  private static Event.Builder xp(String user, int awarded, String receivedAt) {
    Instant at = Instant.parse(receivedAt);
    LocalDate day = VnTime.date(at.toEpochMilli());
    return event("xp.recorded", "sync")
        .setXpRecorded(
            XpRecorded.newBuilder()
                .setXpUuid(UUID.randomUUID().toString())
                .setUserId(user)
                .setSourceType("QUIZ")
                .setXpAwarded(awarded)
                .setOccurredAtMs(at.toEpochMilli())
                .setReceivedAtMs(at.toEpochMilli())
                .setLocalDate(day.toString())
                .setWeekStart(VnTime.weekStart(day).toString())
                .setServerSeq(1));
  }

  private void publish(Event.Builder... events) {
    EventSinkGrpc.newBlockingStub(service.internal())
        .publish(
            PublishRequest.newBuilder()
                .addAllEvents(Arrays.stream(events).map(Event.Builder::build).toList())
                .build());
  }

  private GetWeeklyResponse weekly(String user, WeekSelector week) {
    return GamificationBackendGrpc.newBlockingStub(service.as(user))
        .getWeekly(GetWeeklyRequest.newBuilder().setWeek(week).build());
  }

  private GetWeeklyResponse weekly(String user) {
    return weekly(user, WeekSelector.WEEK_SELECTOR_CURRENT);
  }

  @Test
  void ranksByXpThenByWhoGotThereFirstThenByUserId() {
    publish(registered(A, "anh"), registered(B, "binh"), registered(C, "chi.chi"));
    publish(
        xp(B, 120, "2026-11-12T02:00:00Z"),
        xp(A, 120, "2026-11-12T03:00:00Z"),
        xp(C, 120, "2026-11-12T03:00:00Z"));
    GetWeeklyResponse board = weekly(C);
    assertThat(board.getEntriesList())
        .extracting(LeaderboardEntry::getNickname)
        .containsExactly("binh", "anh", "chi.chi");
    assertThat(board.getEntriesList())
        .extracting(LeaderboardEntry::getRank)
        .containsExactly(1, 2, 3);
    assertThat(board.getMe().getRank()).isEqualTo(3);
    assertThat(board.getEntries(2).getIsMe()).isTrue();
    assertThat(board.getWeekStart()).isEqualTo("2026-11-09");
    assertThat(board.getWeekEnd()).isEqualTo("2026-11-15");
    assertThat(board.getTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(board.toString()).doesNotContain(A, B);
  }

  @Test
  void showsTopFiftyAndAlwaysMe() {
    List<Event.Builder> events = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      String user = "%08d-0000-4000-8000-000000000000".formatted(i);
      events.add(registered(user, "user" + i));
      events.add(xp(user, 100 - i, "2026-11-12T02:00:00Z"));
    }
    publish(events.toArray(Event.Builder[]::new));
    GetWeeklyResponse board = weekly("00000050-0000-4000-8000-000000000000");
    assertThat(board.getEntriesList()).hasSize(50);
    assertThat(board.getMe().getRank()).isEqualTo(51);

    publish(registered(A, "newbie"));
    LeaderboardEntry me = weekly(A).getMe();
    assertThat(me.getRank()).isZero();
    assertThat(me.getXp()).isZero();
    assertThat(me.getNickname()).isEqualTo("newbie");
  }

  @Test
  void duplicateDeliveryChangesNothing() {
    Event.Builder event = xp(A, 40, "2026-11-12T02:00:00Z");
    publish(registered(A, "anh"), event, event, event);
    publish(event.setEventId(UUID.randomUUID().toString()));
    assertThat(weekly(A).getMe().getXp()).isEqualTo(40);
    assertThat(database.getCollection("xp_events").countDocuments()).isEqualTo(1);
  }

  @Test
  void appliesDailyCapAgainAsDefence() {
    publish(
        registered(A, "anh"),
        xp(A, 400, "2026-11-12T02:00:00Z"),
        xp(A, 400, "2026-11-12T02:01:00Z"),
        xp(A, 10, "2026-11-11T02:00:00Z"));
    assertThat(weekly(A).getMe().getXp()).isEqualTo(510);
    var stats =
        GamificationBackendGrpc.newBlockingStub(service.as(A))
            .getStats(GetStatsRequest.getDefaultInstance());
    assertThat(stats.getWeekXp()).isEqualTo(510);
    assertThat(stats.getTotalXp()).isEqualTo(810);
  }

  @Test
  void weekBoundaryFollowsVietnamTime() {
    publish(
        registered(A, "anh"), xp(A, 10, "2026-11-08T16:59:59Z"), xp(A, 20, "2026-11-08T17:00:00Z"));
    assertThat(weekly(A).getMe().getXp()).isEqualTo(20);
    GetWeeklyResponse previous = weekly(A, WeekSelector.WEEK_SELECTOR_PREVIOUS);
    assertThat(previous.getMe().getXp()).isEqualTo(10);
    assertThat(previous.getWeekStart()).isEqualTo("2026-11-02");
    assertFails(
        () ->
            GamificationBackendGrpc.newBlockingStub(service.as(A))
                .getWeekly(GetWeeklyRequest.newBuilder().setWeekValue(99).build()),
        ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void staleProfileAndStreakEventsAreIgnored() {
    publish(
        xp(A, 10, "2026-11-12T02:00:00Z"),
        renamed(A, "second", 20),
        renamed(A, "first", 10),
        registered(A, "zero"),
        streak(A, 5, 200),
        streak(A, 1, 100));
    LeaderboardEntry me = weekly(A).getEntries(0);
    assertThat(me.getNickname()).isEqualTo("second");
    assertThat(me.getStreak()).isEqualTo(5);
  }

  @Test
  void hidesPlayersUntilTheirNicknameArrives() {
    publish(xp(A, 10, "2026-11-12T02:00:00Z"));
    assertThat(weekly(A).getEntriesList()).isEmpty();
    assertThat(weekly(A).getMe().getRank()).isEqualTo(1);
    publish(registered(A, "anh"));
    assertThat(weekly(A).getEntriesList()).hasSize(1);
  }

  @Test
  void deletedUsersDisappearAndLateEventsDoNotReviveThem() {
    publish(registered(A, "anh"), xp(A, 10, "2026-11-12T02:00:00Z"), registered(B, "binh"));
    publish(deleted(A));
    publish(
        deleted(A),
        xp(A, 10, "2026-11-12T02:05:00Z"),
        registered(A, "anh"),
        renamed(A, "anh2", 99),
        streak(A, 3, 9));
    assertThat(weekly(B).getEntriesList()).isEmpty();
    for (String collection : List.of("xp_events", "weekly_xp")) {
      assertThat(database.getCollection(collection).countDocuments()).as(collection).isZero();
    }
    assertThat(database.getCollection("user_stats").countDocuments()).isEqualTo(1);
  }

  @Test
  void schemaIsIdempotentAndRejectsUnknownFields() {
    MongoSchema schema = service.bean(MongoSchema.class);
    schema.apply(database);
    schema.apply(database);
    assertThat(database.getCollection("weekly_xp").listIndexes().into(new ArrayList<>()))
        .extracting(d -> d.getString("name"))
        .contains("ix_weekly_rank", "ix_weekly_user");
    assertThatThrownBy(
            () ->
                database
                    .getCollection("user_stats")
                    .insertOne(
                        new Document("_id", A)
                            .append("updated_at", new java.util.Date())
                            .append("email", "a@b.co")))
        .isInstanceOf(MongoWriteException.class);
  }

  @Test
  void documentsRefusedByTheValidatorAreAPermanentFailure() {
    Event.Builder bad = xp(A, 10, "2026-11-12T02:00:00Z");
    bad.getXpRecordedBuilder().setSourceType("CHEAT");
    assertFails(() -> publish(bad), ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void mongoDownMeansUnavailableQuicklyAndNotServing() {
    try (TestService down =
        TestService.start(
            GamificationApplication.class, "gamification", clock, TestMongo.unreachable())) {
      long start = System.nanoTime();
      assertFails(
          () ->
              GamificationBackendGrpc.newBlockingStub(down.as(A))
                  .getWeekly(GetWeeklyRequest.getDefaultInstance()),
          ErrorCode.SERVICE_UNAVAILABLE);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
      var health = io.grpc.health.v1.HealthGrpc.newBlockingStub(down.anonymous());
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  health
                          .check(io.grpc.health.v1.HealthCheckRequest.getDefaultInstance())
                          .getStatus()
                      == io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING);
    }
  }

  @Test
  void refusesOversizedBatchesAndMissingToken() {
    Event[] many =
        IntStream.range(0, 201).mapToObj(i -> registered(A, "anh").build()).toArray(Event[]::new);
    assertFails(
        () ->
            EventSinkGrpc.newBlockingStub(service.internal())
                .publish(PublishRequest.newBuilder().addAllEvents(List.of(many)).build()),
        ErrorCode.VALIDATION_ERROR);
    TestService.assertInternalTokenRequired(
        () ->
            EventSinkGrpc.newBlockingStub(service.anonymous())
                .publish(PublishRequest.getDefaultInstance()));
  }
}

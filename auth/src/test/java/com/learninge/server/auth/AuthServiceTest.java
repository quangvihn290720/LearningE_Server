package com.learninge.server.auth;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.ChangePasswordRequest;
import com.learninge.proto.app.v1.DeleteAccountRequest;
import com.learninge.proto.app.v1.GetResetQuestionRequest;
import com.learninge.proto.app.v1.LoginRequest;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.ResetPasswordRequest;
import com.learninge.proto.app.v1.UpdateMeRequest;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.AuthBackendGrpc.AuthBackendBlockingStub;
import com.learninge.proto.internal.v1.BackendLogoutRequest;
import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.ExportProfilesRequest;
import com.learninge.proto.internal.v1.GetProfileRequest;
import com.learninge.proto.internal.v1.TokenValidatorGrpc;
import com.learninge.proto.internal.v1.ValidateTokenRequest;
import com.learninge.proto.internal.v1.ValidateTokenResponse;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.security.Digests;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestService;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-SRV-01..14: dịch vụ auth thật, SQLite thật, gọi qua gRPC. */
class AuthServiceTest {
  private static final String PASSWORD = "abc12345";

  @TempDir Path dir;

  private final MutableClock clock = MutableClock.at("2026-11-10T02:00:00Z");
  private TestService service;
  private SqliteDatabase db;

  @BeforeEach
  void setUp() {
    start();
  }

  private void start() {
    service =
        TestService.start(
            AuthApplication.class,
            "auth",
            clock,
            "--learninge.auth.db-path=" + dir.resolve("auth.db"),
            "--learninge.auth.server-key=" + "A".repeat(43) + "=",
            "--learninge.background-enabled=false");
    db = service.bean(SqliteDatabase.class);
  }

  @AfterEach
  void tearDown() {
    service.close();
  }

  private AuthBackendBlockingStub auth() {
    return AuthBackendGrpc.newBlockingStub(service.internal());
  }

  private AuthBackendBlockingStub authAs(String userId) {
    return AuthBackendGrpc.newBlockingStub(service.as(userId));
  }

  private static RegisterRequest.Builder registration(String email, String nickname) {
    return RegisterRequest.newBuilder()
        .setEmail(email)
        .setPassword(PASSWORD)
        .setNickname(nickname)
        .setDisplayName("Minh Anh")
        .setSecurityQuestionId(3)
        .setSecurityAnswer(" Hà Nội ")
        .setDeviceName("Pixel");
  }

  private AuthSession register(String email, String nickname) {
    return auth().register(registration(email, nickname).build());
  }

  private AuthSession login(String email, String password) {
    return auth().login(LoginRequest.newBuilder().setEmail(email).setPassword(password).build());
  }

  private ValidateTokenResponse validate(String token) {
    return TokenValidatorGrpc.newBlockingStub(service.internal())
        .validateToken(ValidateTokenRequest.newBuilder().setTokenSha256(sha256(token)).build());
  }

  private static ByteString sha256(String token) {
    return ByteString.copyFrom(Digests.sha256(token.getBytes(StandardCharsets.US_ASCII)));
  }

  private void assertTokenRejected(String token) {
    assertFails(() -> validate(token), ErrorCode.UNAUTHENTICATED);
  }

  private long count(String sql) {
    return db.reader().sql("SELECT COUNT(*) FROM " + sql).query(Long.class).single();
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

  // --- Register -------------------------------------------------------------------------------

  @Test
  void registerCreatesAccountSessionAndEventsAtomically() {
    AuthSession session = register(" Minh@Gmail.COM ", "minh01");

    assertThat(session.getUser().getEmail()).isEqualTo("minh@gmail.com");
    assertThat(session.getToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(session.getExpiresAtMs()).isEqualTo(clock.millis() + Duration.ofDays(30).toMillis());
    assertThat(validate(session.getToken()).getUserId()).isEqualTo(session.getUser().getId());

    Event sync = events("user.registered", "sync").getFirst();
    Event game = events("user.registered", "gamification").getFirst();
    Event insight = events("user.registered", "insight").getFirst();
    assertThat(List.of(sync.getEventId(), game.getEventId(), insight.getEventId()))
        .containsOnly(sync.getEventId());
    assertThat(game.getUserRegistered().getNickname()).isEqualTo("minh01");
    assertThat(sync.getUserRegistered().getNickname()).isEmpty();
    assertThat(insight.getUserRegistered().getUserId()).isEmpty();
    assertThat(insight.getUserRegistered().getUserHmac()).hasSize(32);
    assertThat(insight.toString()).doesNotContain(session.getUser().getId(), "minh");
  }

  @Test
  void databaseHoldsNoPlainSecrets() throws Exception {
    AuthSession session = register("minh@gmail.com", "minh01");
    db.backupTo(dir.resolve("dump.db"));
    String dump =
        new String(
            java.nio.file.Files.readAllBytes(dir.resolve("dump.db")), StandardCharsets.ISO_8859_1);
    assertThat(dump).doesNotContain(PASSWORD, session.getToken(), "ha noi");
  }

  @Test
  void registerRejectsDuplicatesIgnoringCase() {
    register("minh@gmail.com", "minh01");
    assertFails(() -> register("MINH@gmail.com", "other1"), ErrorCode.EMAIL_TAKEN);
    assertFails(() -> register("other@gmail.com", "MINH01"), ErrorCode.NICKNAME_TAKEN);
    assertThat(count("users")).isEqualTo(1);
    assertThat(count("outbox")).isEqualTo(3);
  }

  @Test
  void parallelRegistrationsOfOneEmailCreateOneAccount() {
    List<CompletableFuture<Boolean>> all =
        IntStream.range(0, 6)
            .mapToObj(
                i ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            register("minh@gmail.com", "minh0" + i);
                            return true;
                          } catch (StatusRuntimeException e) {
                            return false;
                          }
                        }))
            .toList();
    assertThat(all.stream().filter(CompletableFuture::join)).hasSize(1);
    assertThat(count("users")).isEqualTo(1);
  }

  @Test
  void registerReportsEveryInvalidField() {
    Metadata trailers =
        assertFails(
            () ->
                auth()
                    .register(
                        registration("bad", "x")
                            .setPassword("short")
                            .setDisplayName(" ")
                            .setSecurityQuestionId(6)
                            .setSecurityAnswer("a")
                            .setDeviceName("d".repeat(61))
                            .build()),
            ErrorCode.VALIDATION_ERROR);
    assertThat(trailers.get(MetadataKeys.ERROR_FIELDS))
        .contains(
            "email=TOO_SHORT",
            "password=TOO_SHORT",
            "nickname=TOO_SHORT",
            "display_name=REQUIRED",
            "security_question_id=OUT_OF_RANGE",
            "security_answer=TOO_SHORT",
            "device_name=TOO_LONG");
    assertThat(count("users")).isZero();
  }

  // --- Login ----------------------------------------------------------------------------------

  @Test
  void loginIssuesOneTokenPerDevice() {
    AuthSession first = register("minh@gmail.com", "minh01");
    AuthSession second = login(" MINH@gmail.com", PASSWORD);
    assertThat(second.getToken()).isNotEqualTo(first.getToken());
    validate(first.getToken());
    validate(second.getToken());
  }

  @Test
  void wrongPasswordAndUnknownEmailLookTheSame() {
    register("minh@gmail.com", "minh01");
    assertFails(() -> login("minh@gmail.com", "wrong123"), ErrorCode.INVALID_CREDENTIALS);
    assertFails(() -> login("ghost@gmail.com", "wrong123"), ErrorCode.INVALID_CREDENTIALS);
    assertThat(events("auth.login_failed", "insight"))
        .extracting(e -> e.getAuthLoginFailed().getUserHmac().length())
        .containsExactly(32, 0);
    assertThat(count("outbox WHERE type LIKE 'auth.%' AND destination <> 'insight'")).isZero();
    assertThat(count("auth_attempts WHERE subject LIKE '%ghost%'")).isZero();
  }

  @Test
  void realAndUnknownEmailsShareTheSameLockSchedule() {
    register("minh@gmail.com", "minh01");
    for (String email : List.of("minh@gmail.com", "ghost@gmail.com")) {
      List<String> locks = new ArrayList<>();
      for (int level = 0; level < 3; level++) {
        for (int i = 0; i < 4; i++) {
          assertFails(() -> login(email, "wrong123"), ErrorCode.INVALID_CREDENTIALS);
        }
        Metadata locked = assertFails(() -> login(email, "wrong123"), ErrorCode.ACCOUNT_LOCKED);
        String wait = locked.get(MetadataKeys.RETRY_AFTER_SECONDS);
        locks.add(wait);
        // Đúng mật khẩu trong lúc khóa vẫn bị từ chối và không tăng bậc.
        assertFails(() -> login(email, PASSWORD), ErrorCode.ACCOUNT_LOCKED);
        clock.advance(Duration.ofSeconds(Long.parseLong(wait)));
      }
      assertThat(locks).containsExactly("60", "120", "240");
    }
    assertThat(login("minh@gmail.com", PASSWORD).getToken()).isNotEmpty();
    assertThat(count("users WHERE failed_login_count = 0 AND login_lock_count = 0")).isEqualTo(1);
    assertThat(events("auth.locked", "insight")).hasSize(6);
  }

  @Test
  void parallelWrongLoginsGainExactlyOneLockLevel() {
    register("minh@gmail.com", "minh01");
    CompletableFuture<?>[] all =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    CompletableFuture.runAsync(
                        () -> {
                          try {
                            login("minh@gmail.com", "wrong123");
                          } catch (StatusRuntimeException expected) {
                            // đếm ở cơ sở dữ liệu
                          }
                        }))
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(all).join();
    assertThat(count("users WHERE login_lock_count = 1 AND failed_login_count = 0")).isEqualTo(1);
  }

  @Test
  void loginValidatesInput() {
    assertFails(() -> login("", ""), ErrorCode.VALIDATION_ERROR);
    assertFails(() -> login("minh@gmail.com", "a1".repeat(40)), ErrorCode.VALIDATION_ERROR);
  }

  // --- Token ----------------------------------------------------------------------------------

  @Test
  void logoutRevokesOnlyTheCurrentDevice() {
    AuthSession a = register("minh@gmail.com", "minh01");
    AuthSession b = login("minh@gmail.com", PASSWORD);
    auth().logout(BackendLogoutRequest.newBuilder().setTokenSha256(sha256(a.getToken())).build());
    assertTokenRejected(a.getToken());
    validate(b.getToken());
  }

  @Test
  void tokenExpiresAfterThirtyDays() {
    AuthSession session = register("minh@gmail.com", "minh01");
    clock.advance(Duration.ofDays(30).minusSeconds(1));
    assertThat(validate(session.getToken()).getUserHmac()).matches("[0-9a-f]{32}");
    clock.advance(Duration.ofSeconds(1));
    assertTokenRejected(session.getToken());
    assertTokenRejected("unknown");
  }

  @Test
  void validateRequiresASha256() {
    assertFails(
        () ->
            TokenValidatorGrpc.newBlockingStub(service.internal())
                .validateToken(
                    ValidateTokenRequest.newBuilder()
                        .setTokenSha256(ByteString.copyFromUtf8("raw-token"))
                        .build()),
        ErrorCode.VALIDATION_ERROR);
  }

  // --- Profile --------------------------------------------------------------------------------

  @Test
  void updateMeChangesOnlyGivenFields() {
    AuthSession session = register("minh@gmail.com", "minh01");
    String id = session.getUser().getId();
    var updated =
        authAs(id).updateMe(UpdateMeRequest.newBuilder().setNickname("minh02").build()).getUser();
    assertThat(updated.getNickname()).isEqualTo("minh02");
    assertThat(updated.getDisplayName()).isEqualTo("Minh Anh");
    assertThat(updated.getEmail()).isEqualTo("minh@gmail.com");

    var versions = new ArrayList<Long>();
    authAs(id).updateMe(UpdateMeRequest.newBuilder().setDisplayName("Minh B").build());
    events("user.profile_changed", "gamification")
        .forEach(e -> versions.add(e.getUserProfileChanged().getVersion()));
    assertThat(versions).hasSize(2).isSorted().doesNotHaveDuplicates();
    assertThat(events("user.profile_changed", "sync")).isEmpty();
  }

  @Test
  void updateMeRejectsTakenOrInvalidNickname() {
    register("a@gmail.com", "taken1");
    String id = register("b@gmail.com", "minh01").getUser().getId();
    assertFails(
        () -> authAs(id).updateMe(UpdateMeRequest.newBuilder().setNickname("TAKEN1").build()),
        ErrorCode.NICKNAME_TAKEN);
    assertFails(
        () -> authAs(id).updateMe(UpdateMeRequest.newBuilder().setNickname("").build()),
        ErrorCode.VALIDATION_ERROR);
    assertThat(count("outbox WHERE type = 'user.profile_changed'")).isZero();
  }

  @Test
  void userRpcsRequireAUser() {
    assertFails(
        () -> auth().getProfile(GetProfileRequest.getDefaultInstance()), ErrorCode.UNAUTHENTICATED);
    assertFails(
        () ->
            authAs("11111111-2222-4333-8444-555555555555")
                .getProfile(GetProfileRequest.getDefaultInstance()),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void exportProfilesPagesWithoutPrivateData() {
    for (int i = 0; i < 3; i++) {
      register("u" + i + "@gmail.com", "user0" + i);
    }
    var first = auth().exportProfiles(ExportProfilesRequest.newBuilder().setLimit(2).build());
    assertThat(first.getHasMore()).isTrue();
    assertThat(first.getProfilesList()).hasSize(2);
    var second =
        auth()
            .exportProfiles(
                ExportProfilesRequest.newBuilder()
                    .setLimit(100_000)
                    .setAfterId(first.getNextAfterId())
                    .build());
    assertThat(second.getHasMore()).isFalse();
    assertThat(second.getProfilesList()).extracting(p -> p.getNickname()).containsExactly("user02");
    assertThat(second.toString()).doesNotContain("gmail");
  }

  // --- Change password ------------------------------------------------------------------------

  private String change(String userId, String current, String replacement) {
    return authAs(userId)
        .changePassword(
            ChangePasswordRequest.newBuilder()
                .setCurrentPassword(current)
                .setNewPassword(replacement)
                .build())
        .getToken();
  }

  @Test
  void changePasswordRevokesEveryOldToken() {
    AuthSession a = register("minh@gmail.com", "minh01");
    AuthSession b = login("minh@gmail.com", PASSWORD);
    String fresh = change(a.getUser().getId(), PASSWORD, "newpass99");
    assertTokenRejected(a.getToken());
    assertTokenRejected(b.getToken());
    validate(fresh);
    login("minh@gmail.com", "newpass99");
    assertFails(() -> login("minh@gmail.com", PASSWORD), ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void changePasswordRejectsBadInput() {
    String id = register("minh@gmail.com", "minh01").getUser().getId();
    assertFails(() -> change(id, PASSWORD, PASSWORD), ErrorCode.VALIDATION_ERROR);
    assertFails(() -> change(id, PASSWORD, "weak"), ErrorCode.VALIDATION_ERROR);
    for (int i = 0; i < 4; i++) {
      assertFails(
          () -> change(id, "wrong123", "newpass99"),
          Status.Code.PERMISSION_DENIED,
          ErrorCode.INVALID_CREDENTIALS);
    }
    assertFails(() -> change(id, "wrong123", "newpass99"), ErrorCode.RATE_LIMITED);
    assertFails(() -> change(id, PASSWORD, "newpass99"), ErrorCode.RATE_LIMITED);
    clock.advance(Duration.ofMinutes(15));
    assertThat(change(id, PASSWORD, "newpass99")).isNotEmpty();
  }

  // --- Reset ----------------------------------------------------------------------------------

  private int question(String email) {
    return auth()
        .getResetQuestion(GetResetQuestionRequest.newBuilder().setEmail(email).build())
        .getSecurityQuestionId();
  }

  private AuthSession reset(String email, String answer) {
    return auth()
        .resetPassword(
            ResetPasswordRequest.newBuilder()
                .setEmail(email)
                .setSecurityAnswer(answer)
                .setNewPassword("newpass99")
                .build());
  }

  @Test
  void resetQuestionNeverRevealsWhetherAnEmailExists() {
    register("minh@gmail.com", "minh01");
    assertThat(question("minh@gmail.com")).isEqualTo(3);
    int fake = question("ghost@gmail.com");
    assertThat(fake).isBetween(1, 5).isEqualTo(question("GHOST@gmail.com"));
    service.close();
    start();
    assertThat(question("ghost@gmail.com")).isEqualTo(fake);
  }

  @Test
  void resetAcceptsNormalizedAnswerAndRevokesTokens() {
    AuthSession old = register("minh@gmail.com", "minh01");
    for (int i = 0; i < 5; i++) {
      try {
        login("minh@gmail.com", "wrong123");
      } catch (StatusRuntimeException expected) {
        // khóa đăng nhập trước
      }
    }
    AuthSession session = reset("minh@gmail.com", "HA NOI");
    assertTokenRejected(old.getToken());
    validate(session.getToken());
    // Đặt lại thành công xóa luôn bộ đếm đăng nhập.
    login("minh@gmail.com", "newpass99");
  }

  @Test
  void resetLocksIndependentlyFromLogin() {
    register("minh@gmail.com", "minh01");
    for (String email : List.of("minh@gmail.com", "ghost@gmail.com")) {
      for (int i = 0; i < 4; i++) {
        assertFails(() -> reset(email, "wrong"), ErrorCode.INVALID_SECURITY_ANSWER);
      }
      Metadata locked = assertFails(() -> reset(email, "wrong"), ErrorCode.RESET_LOCKED);
      assertThat(locked.get(MetadataKeys.RETRY_AFTER_SECONDS)).isEqualTo("60");
    }
    login("minh@gmail.com", PASSWORD);
  }

  // --- Delete ---------------------------------------------------------------------------------

  @Test
  void deleteAccountRemovesEveryRowOfTheUserOnly() {
    AuthSession gone = register("gone@gmail.com", "gone01");
    AuthSession kept = register("kept@gmail.com", "kept01");
    authAs(gone.getUser().getId())
        .deleteAccount(DeleteAccountRequest.newBuilder().setPassword(PASSWORD).build());

    assertThat(count("users")).isEqualTo(1);
    assertThat(count("security_answers")).isEqualTo(1);
    assertThat(count("auth_tokens")).isEqualTo(1);
    assertTokenRejected(gone.getToken());
    validate(kept.getToken());
    for (String destination : List.of("sync", "gamification", "insight")) {
      assertThat(events("user.deleted", destination)).hasSize(1);
    }
    assertThat(events("user.deleted", "insight").getFirst().toString())
        .doesNotContain(gone.getUser().getId());
    // Email dùng lại được.
    register("gone@gmail.com", "gone01");
  }

  @Test
  void deleteAccountNeedsThePassword() {
    String id = register("minh@gmail.com", "minh01").getUser().getId();
    assertFails(
        () ->
            authAs(id)
                .deleteAccount(DeleteAccountRequest.newBuilder().setPassword("wrong123").build()),
        Status.Code.PERMISSION_DENIED,
        ErrorCode.INVALID_CREDENTIALS);
    assertFails(
        () -> authAs(id).deleteAccount(DeleteAccountRequest.getDefaultInstance()),
        ErrorCode.VALIDATION_ERROR);
    assertThat(count("users")).isEqualTo(1);
  }

  // --- Vận hành -------------------------------------------------------------------------------

  @Test
  void everyInternalRpcNeedsTheInternalToken() {
    TestService.assertInternalTokenRequired(
        () ->
            AuthBackendGrpc.newBlockingStub(service.anonymous())
                .exportProfiles(ExportProfilesRequest.getDefaultInstance()));
    TestService.assertInternalTokenRequired(
        () ->
            AuthBackendGrpc.newBlockingStub(service.with(MetadataKeys.INTERNAL_AUTH, "wrong-token"))
                .exportProfiles(ExportProfilesRequest.getDefaultInstance()));
  }

  @Test
  void cleanupRemovesOnlyLongEndedTokens() {
    register("minh@gmail.com", "minh01");
    var cleanup = service.bean(com.learninge.server.auth.service.AuthCleanup.class);
    clock.advance(Duration.ofDays(36));
    cleanup.deleteExpired();
    assertThat(count("auth_tokens")).isEqualTo(1);
    clock.advance(Duration.ofDays(2));
    cleanup.deleteExpired();
    assertThat(count("auth_tokens")).isZero();
  }

  @Test
  void prodRefusesToStartWithoutKeys() {
    assertThatThrownBy(
            () ->
                TestService.start(
                    AuthApplication.class,
                    "auth",
                    clock,
                    "--learninge.env=prod",
                    "--learninge.auth.db-path=" + dir.resolve("prod.db"),
                    "--learninge.background-enabled=false"))
        .hasStackTraceContaining("LEARNINGE_SERVER_KEY");
  }

  @Test
  void healthNeedsNoToken() throws Exception {
    var health = io.grpc.health.v1.HealthGrpc.newBlockingStub(service.anonymous());
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                health.check(io.grpc.health.v1.HealthCheckRequest.getDefaultInstance()).getStatus()
                    == io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
  }
}

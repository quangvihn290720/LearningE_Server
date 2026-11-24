package com.learninge.server.edge;

import static com.learninge.server.common.testing.TestService.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.learninge.proto.app.v1.AuthServiceGrpc;
import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.ChangePasswordRequest;
import com.learninge.proto.app.v1.ContentServiceGrpc;
import com.learninge.proto.app.v1.DeleteAccountRequest;
import com.learninge.proto.app.v1.GetManifestRequest;
import com.learninge.proto.app.v1.GetMeRequest;
import com.learninge.proto.app.v1.GetMeResponse;
import com.learninge.proto.app.v1.GetResetQuestionRequest;
import com.learninge.proto.app.v1.LeaderboardServiceGrpc;
import com.learninge.proto.app.v1.ListSessionsRequest;
import com.learninge.proto.app.v1.LoginRequest;
import com.learninge.proto.app.v1.LogoutRequest;
import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.ResetPasswordRequest;
import com.learninge.proto.app.v1.RevokeOtherSessionsRequest;
import com.learninge.proto.app.v1.RevokeSessionRequest;
import com.learninge.proto.app.v1.SyncServiceGrpc;
import com.learninge.proto.app.v1.WordData;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.BackendListSessionsRequest;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.proto.internal.v1.ExportProfilesRequest;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.proto.internal.v1.GetStatsResponse;
import com.learninge.proto.internal.v1.InsightBackendGrpc;
import com.learninge.proto.internal.v1.LogRequestsRequest;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.proto.internal.v1.TokenValidatorGrpc;
import com.learninge.proto.internal.v1.ValidateTokenRequest;
import com.learninge.server.common.config.AppEnv;
import com.learninge.server.common.config.Secret;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.security.Digests;
import com.learninge.server.common.testing.MutableClock;
import com.learninge.server.common.testing.TestService;
import com.learninge.server.edge.ops.DemoProperties;
import com.learninge.server.edge.ops.DemoSeedCommand;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * TC-SRV-46..52, TC-RPC-23..30, TC-SVC-03..12: edge thật trước năm dịch vụ giả.
 */
class EdgeServiceTest {
    static final String USER = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String OTHER = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String TOKEN = "t".repeat(43);

    private final MutableClock clock = MutableClock.at("2026-11-12T03:00:00Z");
    private FakeBackends backends;
    private TestService edge;

    @BeforeEach
    void setUp() throws Exception {
        backends = new FakeBackends();
        edge = start();
        warmUp();
        backends.allow(sha256(TOKEN), USER, clock.millis() + Duration.ofDays(30).toMillis());
    }

    /**
     * Lời gọi đầu tiên phải mở kết nối; máy CI chậm có thể vượt deadline 500 ms của ValidateToken.
     */
    private void warmUp() {
        String warm = "w".repeat(43);
        backends.allow(sha256(warm), OTHER, clock.millis() + 60_000);
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .ignoreExceptions()
                .until(() -> !getMe(warm).getStatsPartial());
        backends.received.clear();
    }

    private TestService start(String... extra) {
        List<String> args = new ArrayList<>(List.of(extra));
        for (String service : List.of("auth", "sync", "content", "gamification", "insight")) {
            args.add("--learninge.services." + service + "=127.0.0.1:" + backends.port());
        }
        return TestService.start(EdgeApplication.class, "edge", clock, args.toArray(String[]::new));
    }

    @AfterEach
    void tearDown() {
        edge.close();
        backends.close();
    }

    private static ByteString sha256(String token) {
        return ByteString.copyFrom(Digests.sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Metadata app(String token) {
        Metadata headers = new Metadata();
        headers.put(MetadataKeys.CLIENT_VERSION, "1");
        if (token != null) {
            headers.put(MetadataKeys.AUTHORIZATION, "Bearer " + token);
        }
        return headers;
    }

    private Channel channel(String token) {
        return edge.with(app(token));
    }

    private AuthServiceGrpc.AuthServiceBlockingStub auth(String token) {
        return AuthServiceGrpc.newBlockingStub(channel(token));
    }

    private SyncServiceGrpc.SyncServiceBlockingStub sync(String token) {
        return SyncServiceGrpc.newBlockingStub(channel(token));
    }

    private GetMeResponse getMe(String token) {
        return auth(token).getMe(GetMeRequest.getDefaultInstance());
    }

    private AuthSession login(String email) {
        return auth(null).login(LoginRequest.newBuilder().setEmail(email).build());
    }

    // --- Xác thực ---------------------------------------------------------------------------------

    private static final List<ServiceDescriptor> PUBLIC_API =
            List.of(
                    AuthServiceGrpc.getServiceDescriptor(),
                    SyncServiceGrpc.getServiceDescriptor(),
                    LeaderboardServiceGrpc.getServiceDescriptor(),
                    ContentServiceGrpc.getServiceDescriptor());

    /**
     * Test sinh từ descriptor: RPC mới thêm vào proto tự được kiểm.
     */
    @Test
    void onlyFourAppRpcsWorkWithoutAToken() {
        Set<String> open = new java.util.TreeSet<>();
        for (ServiceDescriptor service : PUBLIC_API) {
            for (MethodDescriptor<?, ?> method : service.getMethods()) {
                try {
                    invoke(channel(null), method);
                    open.add(method.getBareMethodName());
                } catch (StatusRuntimeException e) {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(e.getTrailers().get(MetadataKeys.ERROR_CODE)).isEqualTo("UNAUTHENTICATED");
                }
            }
        }
        assertThat(open).containsExactly("GetResetQuestion", "Login", "Register", "ResetPassword");
    }

    @SuppressWarnings("unchecked")
    private static void invoke(Channel channel, MethodDescriptor<?, ?> method) {
        MethodDescriptor<Message, Message> typed = (MethodDescriptor<Message, Message>) method;
        Message request =
                ((MethodDescriptor.PrototypeMarshaller<Message>) typed.getRequestMarshaller())
                        .getMessagePrototype();
        ClientCalls.blockingUnaryCall(
                channel,
                typed,
                io.grpc.CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                request);
    }

    @Test
    void everyKindOfBadTokenGetsTheSameAnswer() {
        backends.allow(sha256("e".repeat(43)), USER, clock.millis() - 1);
        for (String bad : List.of("short", "!".repeat(43), "u".repeat(43), "t".repeat(44))) {
            assertFails(() -> getMe(bad), ErrorCode.UNAUTHENTICATED);
        }
        Metadata basic = app(null);
        basic.put(MetadataKeys.AUTHORIZATION, "Basic " + TOKEN);
        assertFails(
                () ->
                        AuthServiceGrpc.newBlockingStub(edge.with(basic))
                                .getMe(GetMeRequest.getDefaultInstance()),
                ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void cachesValidationAndSendsOnlyTheHash() {
        for (int i = 0; i < 10; i++) {
            getMe(TOKEN);
        }
        var validations = backends.calls(TokenValidatorGrpc.getValidateTokenMethod());
        assertThat(validations).hasSize(1);
        assertThat(((ValidateTokenRequest) validations.getFirst().request()).getTokenSha256())
                .isEqualTo(sha256(TOKEN));
        for (var call : backends.received) {
            assertThat(call.headers().toString()).doesNotContain(TOKEN);
            assertThat(call.headers().containsKey(MetadataKeys.AUTHORIZATION)).isFalse();
        }
        clock.advance(Duration.ofSeconds(61));
        getMe(TOKEN);
        assertThat(backends.calls(TokenValidatorGrpc.getValidateTokenMethod())).hasSize(2);
    }

    @Test
    void neverCachesPastTheTokenExpiry() {
        String shortLived = "s".repeat(43);
        backends.allow(sha256(shortLived), USER, clock.millis() + 30_000);
        getMe(shortLived);
        clock.advance(Duration.ofSeconds(31));
        backends.revokeAll();
        assertFails(() -> getMe(shortLived), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void revokingRpcsDropTheCacheImmediately() {
        List<Runnable> revokers =
                List.of(
                        () -> auth(TOKEN).logout(LogoutRequest.getDefaultInstance()),
                        () -> auth(TOKEN).changePassword(ChangePasswordRequest.getDefaultInstance()),
                        () -> auth(TOKEN).deleteAccount(DeleteAccountRequest.getDefaultInstance()),
                        () -> auth(null).resetPassword(ResetPasswordRequest.getDefaultInstance()));
        backends.on(AuthBackendGrpc.getResetPasswordMethod(), r -> FakeBackends.session());
        for (Runnable revoker : revokers) {
            backends.allow(sha256(TOKEN), USER, clock.millis() + 3_600_000);
            getMe(TOKEN);
            revoker.run();
            backends.revokeAll();
            assertFails(() -> getMe(TOKEN), ErrorCode.UNAUTHENTICATED);
        }
    }

    @Test
    void changingPasswordOnOneDeviceDropsAllDevicesOfThatUserOnly() {
        String second = "b".repeat(43);
        String stranger = "c".repeat(43);
        backends.allow(sha256(second), USER, clock.millis() + 3_600_000);
        backends.allow(sha256(stranger), OTHER, clock.millis() + 3_600_000);
        getMe(TOKEN);
        getMe(second);
        getMe(stranger);
        auth(TOKEN).changePassword(ChangePasswordRequest.getDefaultInstance());
        backends.revokeAll();
        assertFails(() -> getMe(second), ErrorCode.UNAUTHENTICATED);
        assertThat(getMe(stranger).hasUser()).isTrue();
    }

    @Test
    void logoutSendsTheHashOfTheCurrentToken() {
        auth(TOKEN).logout(LogoutRequest.getDefaultInstance());
        var call = backends.calls(AuthBackendGrpc.getLogoutMethod()).getFirst();
        assertThat(call.request().toString()).isNotEmpty();
        assertThat(
                ((com.learninge.proto.internal.v1.BackendLogoutRequest) call.request())
                        .getTokenSha256())
                .isEqualTo(sha256(TOKEN));
    }

    @Test
    void authOutageNeverLooksLikeAnExpiredSession() {
        backends.fail(TokenValidatorGrpc.getValidateTokenMethod(), Status.UNAVAILABLE);
        assertFails(() -> getMe(TOKEN), ErrorCode.SERVICE_UNAVAILABLE);
        // Sai khóa nội bộ: auth trả UNAUTHENTICATED không chi tiết.
        backends.fail(TokenValidatorGrpc.getValidateTokenMethod(), Status.UNAUTHENTICATED);
        assertFails(() -> getMe(TOKEN), ErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- Ranh giới tin cậy ------------------------------------------------------------------------

    @Test
    void clientSuppliedInternalMetadataNeverReachesBackends() {
        Metadata forged = app(TOKEN);
        forged.put(MetadataKeys.USER_ID, OTHER);
        forged.put(MetadataKeys.USER_ID, OTHER);
        forged.put(MetadataKeys.INTERNAL_AUTH, "guess");
        forged.put(
                Metadata.Key.of("x-user-id-bin", Metadata.BINARY_BYTE_MARSHALLER),
                OTHER.getBytes(StandardCharsets.US_ASCII));
        forged.put(Metadata.Key.of("x-internal-role", Metadata.ASCII_STRING_MARSHALLER), "admin");
        SyncServiceGrpc.newBlockingStub(edge.with(forged)).pull(PullRequest.getDefaultInstance());

        Metadata seen = backends.calls(SyncBackendGrpc.getPullMethod()).getFirst().headers();
        assertThat(seen.getAll(MetadataKeys.USER_ID)).containsExactly(USER);
        assertThat(seen.getAll(MetadataKeys.INTERNAL_AUTH)).containsExactly(TestService.INTERNAL_TOKEN);
        assertThat(seen.keys()).doesNotContain("x-user-id-bin", "x-internal-role");
    }

    @Test
    void publicRpcsCarryNoUserIdEvenIfTheClientSendsOne() {
        Metadata forged = app(null);
        forged.put(MetadataKeys.USER_ID, OTHER);
        AuthServiceGrpc.newBlockingStub(edge.with(forged)).login(LoginRequest.getDefaultInstance());
        Metadata seen = backends.calls(AuthBackendGrpc.getLoginMethod()).getFirst().headers();
        assertThat(seen.containsKey(MetadataKeys.USER_ID)).isFalse();
    }

    @Test
    void internalServicesAreNotExposed() {
        assertThatThrownBy(
                () ->
                        AuthBackendGrpc.newBlockingStub(channel(TOKEN))
                                .exportProfiles(ExportProfilesRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
        assertThat(backends.calls(AuthBackendGrpc.getExportProfilesMethod())).isEmpty();
    }

    @Test
    void reflectionIsRefusedInProduction() {
        assertThatThrownBy(
                () -> start("--learninge.env=prod", "--learninge.edge.reflection=true").close())
                .hasStackTraceContaining("LEARNINGE_GRPC_REFLECTION");
    }

    // --- Giới hạn ---------------------------------------------------------------------------------

    @Test
    void limitsLoginsPerIpBeforeReachingAuth() {
        for (int i = 0; i < 5; i++) {
            login("u" + i + "@b.co");
        }
        Metadata limited = assertFails(() -> login("x@b.co"), ErrorCode.RATE_LIMITED);
        assertThat(limited.get(MetadataKeys.RETRY_AFTER_SECONDS)).isEqualTo("60");
        assertThat(backends.calls(AuthBackendGrpc.getLoginMethod())).hasSize(5);
        clock.advance(Duration.ofSeconds(60));
        login("x@b.co");
    }

    @Test
    void limitsRegistrationsAndResets() {
        for (int i = 0; i < 10; i++) {
            auth(null).register(RegisterRequest.getDefaultInstance());
        }
        Metadata limited =
                assertFails(
                        () -> auth(null).register(RegisterRequest.getDefaultInstance()),
                        ErrorCode.RATE_LIMITED);
        assertThat(limited.get(MetadataKeys.RETRY_AFTER_SECONDS)).isEqualTo("3600");
        for (int i = 0; i < 5; i++) {
            auth(null).getResetQuestion(GetResetQuestionRequest.getDefaultInstance());
        }
        assertFails(
                () -> auth(null).resetPassword(ResetPasswordRequest.getDefaultInstance()),
                ErrorCode.RATE_LIMITED);
    }

    @Test
    void limitsPushesAndOtherCallsPerToken() {
        String other = "o".repeat(43);
        backends.allow(sha256(other), OTHER, clock.millis() + 3_600_000);
        for (int i = 0; i < 30; i++) {
            sync(TOKEN).push(PushRequest.getDefaultInstance());
        }
        assertFails(() -> sync(TOKEN).push(PushRequest.getDefaultInstance()), ErrorCode.RATE_LIMITED);
        sync(other).push(PushRequest.getDefaultInstance());
        for (int i = 0; i < 120; i++) {
            sync(TOKEN).pull(PullRequest.getDefaultInstance());
        }
        assertFails(() -> sync(TOKEN).pull(PullRequest.getDefaultInstance()), ErrorCode.RATE_LIMITED);
        sync(other).pull(PullRequest.getDefaultInstance());
    }

    @Test
    void refusesMessagesOverOneMebibyte() {
        PushRequest huge =
                PushRequest.newBuilder()
                        .addChanges(
                                Change.newBuilder().setWord(WordData.newBuilder().setTerm("x".repeat(1 << 20))))
                        .build();
        assertFails(() -> sync(TOKEN).push(huge), ErrorCode.PAYLOAD_TOO_LARGE);
        PushRequest transportLimit = huge.toBuilder().addChanges(huge.getChanges(0)).build();
        assertThatThrownBy(() -> sync(TOKEN).push(transportLimit))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
        assertThat(backends.calls(SyncBackendGrpc.getPushMethod())).isEmpty();
    }

    @Test
    void acceptsMessagesUpToOneMebibyte() {
        sync(TOKEN)
                .push(
                        PushRequest.newBuilder()
                                .addChanges(
                                        Change.newBuilder()
                                                .setWord(WordData.newBuilder().setTerm("x".repeat((1 << 20) - 100))))
                                .build());
        assertThat(backends.calls(SyncBackendGrpc.getPushMethod())).hasSize(1);
    }

    @Test
    void guessingTokensStopsReachingAuthButCachedUsersAreUnaffected() {
        getMe(TOKEN);
        for (int i = 0; i < 60; i++) {
            String guess = "guess" + String.format("%038d", i);
            assertFails(() -> getMe(guess), ErrorCode.UNAUTHENTICATED);
        }
        int asked = backends.calls(TokenValidatorGrpc.getValidateTokenMethod()).size();
        assertFails(() -> getMe("z".repeat(43)), ErrorCode.RATE_LIMITED);
        assertThat(backends.calls(TokenValidatorGrpc.getValidateTokenMethod())).hasSize(asked);
        getMe(TOKEN);
        clock.advance(Duration.ofSeconds(61));
        assertFails(() -> getMe("z".repeat(43)), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void oldOrMissingClientVersionsMustUpgrade() {
        for (String version : new String[]{null, "0", "abc", "-5"}) {
            Metadata headers = new Metadata();
            if (version != null) {
                headers.put(MetadataKeys.CLIENT_VERSION, version);
            }
            assertFails(
                    () ->
                            AuthServiceGrpc.newBlockingStub(edge.with(headers))
                                    .login(LoginRequest.getDefaultInstance()),
                    ErrorCode.UPGRADE_REQUIRED);
        }
    }

    // --- Chuyển tiếp ------------------------------------------------------------------------------

    @Test
    void forwardsRequestsAndResponsesUnchanged() {
        backends.on(AuthBackendGrpc.getLoginMethod(), r -> FakeBackends.session());
        assertThat(login("a@b.co")).isEqualTo(FakeBackends.session());
        assertThat(backends.calls(AuthBackendGrpc.getLoginMethod()).getFirst().request())
                .isEqualTo(LoginRequest.newBuilder().setEmail("a@b.co").build());
    }

    @Test
    void businessErrorsKeepCodeAndTrailers() {
        backends.fail(
                AuthBackendGrpc.getLoginMethod(), AppException.retryAfter(ErrorCode.ACCOUNT_LOCKED, 240));
        Metadata locked = assertFails(() -> login("a@b.co"), ErrorCode.ACCOUNT_LOCKED);
        assertThat(locked.get(MetadataKeys.RETRY_AFTER_SECONDS)).isEqualTo("240");
        assertThat(locked.getAll(MetadataKeys.REQUEST_ID)).hasSize(1);

        backends.fail(
                AuthBackendGrpc.getRegisterMethod(), AppException.invalid("email", "INVALID_FORMAT"));
        Metadata invalid =
                assertFails(
                        () -> auth(null).register(RegisterRequest.getDefaultInstance()),
                        ErrorCode.VALIDATION_ERROR);
        assertThat(invalid.get(MetadataKeys.ERROR_FIELDS)).isEqualTo("email=INVALID_FORMAT");
    }

    @Test
    void infrastructureErrorsAreTranslatedAndNeverRetried() {
        backends.fail(SyncBackendGrpc.getPullMethod(), Status.UNAVAILABLE);
        assertFails(
                () -> sync(TOKEN).pull(PullRequest.getDefaultInstance()), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(backends.calls(SyncBackendGrpc.getPullMethod())).hasSize(1);

        for (Status status :
                List.of(
                        Status.INTERNAL.withDescription("SELECT * FROM secrets"),
                        Status.UNKNOWN,
                        Status.UNAUTHENTICATED,
                        Status.UNIMPLEMENTED)) {
            backends.fail(SyncBackendGrpc.getPullMethod(), status);
            assertThatThrownBy(() -> sync(TOKEN).pull(PullRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> {
                                assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                                assertThat(e.getStatus().getDescription()).doesNotContain("SELECT");
                            });
        }
    }

    @Test
    void unreachableBackendIsUnavailable() {
        backends.close();
        TestService alone = edge;
        assertFails(
                () ->
                        ContentServiceGrpc.newBlockingStub(alone.with(app(TOKEN)))
                                .getManifest(GetManifestRequest.getDefaultInstance()),
                ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void passesOnLessTimeThanTheAppGave() {
        sync(TOKEN).withDeadlineAfter(2, TimeUnit.SECONDS).pull(PullRequest.getDefaultInstance());
        assertThat(backends.calls(SyncBackendGrpc.getPullMethod()).getFirst().deadlineMs())
                .isBetween(500L, 1900L);
        sync(TOKEN).pull(PullRequest.getDefaultInstance());
        assertThat(backends.calls(SyncBackendGrpc.getPullMethod()).getLast().deadlineMs())
                .isBetween(10_000L, 13_000L);
        ContentServiceGrpc.newBlockingStub(channel(TOKEN))
                .getManifest(GetManifestRequest.getDefaultInstance());
        assertThat(backends.calls(ContentBackendGrpc.getGetManifestMethod()).getFirst().deadlineMs())
                .isBetween(1000L, 3000L);
    }

    @Test
    void slowBackendEndsInDeadlineExceeded() {
        backends.delay(
                SyncBackendGrpc.getPullMethod(),
                3000,
                com.learninge.proto.app.v1.PullResponse.getDefaultInstance());
        long start = System.nanoTime();
        assertThatThrownBy(
                () ->
                        sync(TOKEN)
                                .withDeadlineAfter(700, TimeUnit.MILLISECONDS)
                                .pull(PullRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    // --- GetMe ------------------------------------------------------------------------------------

    @Test
    void getMeCombinesThreeServices() {
        GetMeResponse me = getMe(TOKEN);
        assertThat(me.getUser().getDisplayName()).isEqualTo("Minh");
        assertThat(me.getStats().getTotalXp()).isEqualTo(900);
        assertThat(me.getStats().getWeekXp()).isEqualTo(120);
        assertThat(me.getStats().getCurrentStreak()).isEqualTo(5);
        assertThat(me.getStatsPartial()).isFalse();
    }

    @Test
    void getMeSurvivesSlowOrBrokenOptionalParts() {
        backends.delay(
                GamificationBackendGrpc.getGetStatsMethod(), 1500, GetStatsResponse.getDefaultInstance());
        getMe(TOKEN); // làm nóng kết nối
        long start = System.nanoTime();
        GetMeResponse slow = getMe(TOKEN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
        assertThat(slow.getStatsPartial()).isTrue();
        assertThat(slow.getStats().getTotalXp()).isEqualTo(900);
        assertThat(slow.getStats().getWeekXp()).isZero();

        backends.on(
                GamificationBackendGrpc.getGetStatsMethod(),
                r -> GetStatsResponse.newBuilder().setTotalXp(800).setWeekXp(120).build());
        backends.fail(SyncBackendGrpc.getGetStreakMethod(), Status.UNAVAILABLE);
        GetMeResponse noSync = getMe(TOKEN);
        assertThat(noSync.getStatsPartial()).isTrue();
        assertThat(noSync.getStats().getTotalXp()).isEqualTo(800);
        assertThat(noSync.getStats().getCurrentStreak()).isZero();

        backends.fail(AuthBackendGrpc.getGetProfileMethod(), Status.UNAVAILABLE);
        assertFails(() -> getMe(TOKEN), ErrorCode.SERVICE_UNAVAILABLE);
    }

    // --- Metadata, health, log --------------------------------------------------------------------

    @Test
    void everyResponseCarriesRequestIdVersionAndTime() {
        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        Channel capturing =
                ClientInterceptors.intercept(
                        channel(TOKEN), MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
        AuthServiceGrpc.newBlockingStub(capturing).getMe(GetMeRequest.getDefaultInstance());
        assertThat(headers.get().get(MetadataKeys.REQUEST_ID)).hasSize(36);
        assertThat(headers.get().get(MetadataKeys.SERVER_VERSION)).isNotEmpty();
        assertThat(headers.get().get(MetadataKeys.SERVER_TIME_MS))
                .isEqualTo(Long.toString(clock.millis()));
        assertThat(
                backends
                        .calls(AuthBackendGrpc.getGetProfileMethod())
                        .getFirst()
                        .headers()
                        .get(MetadataKeys.REQUEST_ID))
                .isEqualTo(headers.get().get(MetadataKeys.REQUEST_ID));
    }

    @Test
    void healthReflectsCoreServicesWithoutAToken() {
        var health = HealthGrpc.newBlockingStub(edge.anonymous());
        for (String service : List.of("", "learninge.app.v1.LeaderboardService")) {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(
                            () ->
                                    health
                                            .check(HealthCheckRequest.newBuilder().setService(service).build())
                                            .getStatus()
                                            == ServingStatus.SERVING);
        }
        assertThatThrownBy(
                () -> health.check(HealthCheckRequest.newBuilder().setService("auth").build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void shipsRequestLogsWithoutPersonalData() {
        getMe(TOKEN);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> !backends.calls(InsightBackendGrpc.getLogRequestsMethod()).isEmpty());
        var log =
                ((LogRequestsRequest)
                        backends.calls(InsightBackendGrpc.getLogRequestsMethod()).getFirst().request())
                        .getLogs(0);
        assertThat(log.getRpc()).isEqualTo("learninge.app.v1.AuthService/GetMe");
        assertThat(log.getUserHmac()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(log.toString()).doesNotContain(USER, TOKEN, "127.0.0.1");
    }

    // --- Tài khoản demo -----------------------------------------------------------------------------

    private DemoSeedCommand demo(boolean enabled, AppEnv env) {
        return new DemoSeedCommand(
                edge.bean(InternalChannels.class),
                new DemoProperties(enabled, Secret.valueOf(enabled ? "demo-password-1" : "")),
                env,
                clock);
    }

    @Test
    void demoSeedRegistersAndPushesAsEachUser() {
        backends.on(AuthBackendGrpc.getRegisterMethod(), r -> FakeBackends.session());
        demo(true, AppEnv.DEV).run(new DefaultApplicationArguments());
        assertThat(backends.calls(AuthBackendGrpc.getRegisterMethod())).hasSize(10);
        var pushes = backends.calls(SyncBackendGrpc.getPushMethod());
        assertThat(pushes).hasSize(10);
        assertThat(pushes).allMatch(p -> USER.equals(p.headers().get(MetadataKeys.USER_ID)));
        assertThat(pushes).allMatch(p -> ((PushRequest) p.request()).getChangesCount() <= 500);

        backends.received.clear();
        backends.fail(AuthBackendGrpc.getRegisterMethod(), AppException.of(ErrorCode.EMAIL_TAKEN));
        demo(true, AppEnv.DEV).run(new DefaultApplicationArguments());
        assertThat(backends.calls(SyncBackendGrpc.getPushMethod())).isEmpty();
    }

    @Test
    void demoSeedIsRefusedUnlessSwitchedOn() {
        assertThatThrownBy(() -> demo(false, AppEnv.DEV).run(new DefaultApplicationArguments()))
                .hasMessageContaining("LEARNINGE_DEMO_SEED");
        assertThatThrownBy(() -> demo(true, AppEnv.PROD).run(new DefaultApplicationArguments()))
                .hasMessageContaining("confirm-prod");
        assertThat(backends.calls(AuthBackendGrpc.getRegisterMethod())).isEmpty();
    }

    // --- Phiên đăng nhập ----------------------------------------------------------------------------

    @Test
    void sessionCallsCarryTheCurrentTokenHashAndDropTheCache() {
        auth(TOKEN).listSessions(ListSessionsRequest.getDefaultInstance());
        var list = backends.calls(AuthBackendGrpc.getListSessionsMethod()).getFirst();
        assertThat(((BackendListSessionsRequest) list.request()).getTokenSha256()).isEqualTo(sha256(TOKEN));

        auth(TOKEN).revokeOtherSessions(RevokeOtherSessionsRequest.getDefaultInstance());
        getMe(TOKEN);
        assertThat(backends.calls(TokenValidatorGrpc.getValidateTokenMethod())).hasSize(2);

        backends.fail(AuthBackendGrpc.getRevokeSessionMethod(), AppException.of(ErrorCode.NOT_FOUND));
        assertFails(
                () -> auth(TOKEN).revokeSession(RevokeSessionRequest.newBuilder().setId(7).build()),
                ErrorCode.NOT_FOUND);
        backends.revokeAll();
        assertFails(() -> getMe(TOKEN), ErrorCode.UNAUTHENTICATED);
    }
}

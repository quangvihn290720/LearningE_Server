package com.learninge.server.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.app.v1.GetManifestRequest;
import com.learninge.proto.app.v1.GetManifestResponse;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.server.common.config.Secret;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.testing.TestService;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InterceptorsTest {
    private static final Secret TOKEN = Secret.valueOf(TestService.INTERNAL_TOKEN);
    private static final String USER = "11111111-2222-4333-8444-555555555555";

    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicReference<String> seenMdc = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private void start(Supplier<GetManifestResponse> handler, ServerInterceptor... interceptors)
            throws Exception {
        var service =
                new ContentBackendGrpc.ContentBackendImplBase() {
                    @Override
                    public void getManifest(
                            GetManifestRequest request, StreamObserver<GetManifestResponse> observer) {
                        handled.incrementAndGet();
                        seenMdc.set(MDC.get("request_id"));
                        observer.onNext(handler.get());
                        observer.onCompleted();
                    }
                };
        server =
                NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                        .addService(ServerInterceptors.interceptForward(service, List.of(interceptors)))
                        .build()
                        .start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    }

    private static ServerInterceptor requestId() {
        return new RequestIdServerInterceptor(h -> {
        }, (i, c) -> {
        });
    }

    private static ServerInterceptor errors() {
        return new ErrorMappingInterceptor(e -> null);
    }

    private GetManifestResponse call(Metadata headers) {
        Channel intercepted =
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        return ContentBackendGrpc.newBlockingStub(intercepted)
                .getManifest(GetManifestRequest.getDefaultInstance());
    }

    private static Metadata headers(Metadata.Key<String> key, String value) {
        Metadata headers = new Metadata();
        headers.put(key, value);
        return headers;
    }

    @Test
    void wrongOrMissingInternalTokenNeverReachesHandler() throws Exception {
        start(
                GetManifestResponse::getDefaultInstance,
                new InternalAuthServerInterceptor(TOKEN),
                requestId(),
                errors());
        for (String bad : List.of("x", "", TestService.INTERNAL_TOKEN + "x")) {
            TestService.assertInternalTokenRequired(() -> call(headers(MetadataKeys.INTERNAL_AUTH, bad)));
        }
        TestService.assertInternalTokenRequired(() -> call(new Metadata()));
        assertThat(handled).hasValue(0);

        call(headers(MetadataKeys.INTERNAL_AUTH, TestService.INTERNAL_TOKEN));
        assertThat(handled).hasValue(1);
    }

    @Test
    void userIdIsReadOnlyWhenItIsAUuid() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        start(
                () -> {
                    seen.set(CallerContext.userId());
                    return GetManifestResponse.getDefaultInstance();
                },
                new InternalAuthServerInterceptor(TOKEN),
                errors());
        Metadata valid = headers(MetadataKeys.INTERNAL_AUTH, TestService.INTERNAL_TOKEN);
        valid.put(MetadataKeys.USER_ID, USER);
        call(valid);
        assertThat(seen).hasValue(USER);

        Metadata junk = headers(MetadataKeys.INTERNAL_AUTH, TestService.INTERNAL_TOKEN);
        junk.put(MetadataKeys.USER_ID, "1 OR 1=1");
        TestService.assertFails(() -> call(junk), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void keepsValidRequestIdAndReplacesInvalidOnes() throws Exception {
        start(GetManifestResponse::getDefaultInstance, requestId(), errors());
        call(headers(MetadataKeys.REQUEST_ID, USER));
        assertThat(seenMdc).hasValue(USER);
        call(headers(MetadataKeys.REQUEST_ID, "not-a-uuid"));
        assertThat(seenMdc.get()).isNotEqualTo("not-a-uuid").hasSize(36);
    }

    @Test
    void requestIdIsReturnedEvenWhenTheCallFails() throws Exception {
        start(
                () -> {
                    throw AppException.of(ErrorCode.NOT_FOUND);
                },
                requestId(),
                errors());
        Metadata trailers =
                TestService.assertFails(
                        () -> call(headers(MetadataKeys.REQUEST_ID, USER)), ErrorCode.NOT_FOUND);
        assertThat(trailers.get(MetadataKeys.REQUEST_ID)).isEqualTo(USER);
    }

    @Test
    void unexpectedErrorsBecomeGenericInternal() throws Exception {
        start(
                () -> {
                    throw new IllegalStateException("SELECT secret FROM /etc/passwd");
                },
                requestId(),
                errors());
        StatusRuntimeException error =
                Assertions.catchThrowableOfType(StatusRuntimeException.class, () -> call(new Metadata()));
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(error.getStatus().getDescription()).doesNotContain("secret");
        assertThat(error.getTrailers().get(MetadataKeys.ERROR_CODE)).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void trailersCarryRetryAfterAndFieldErrors() throws Exception {
        AtomicReference<AppException> toThrow =
                new AtomicReference<>(AppException.retryAfter(ErrorCode.ACCOUNT_LOCKED, 60));
        start(
                () -> {
                    throw toThrow.get();
                },
                errors());
        Metadata locked = TestService.assertFails(() -> call(new Metadata()), ErrorCode.ACCOUNT_LOCKED);
        assertThat(locked.get(MetadataKeys.RETRY_AFTER_SECONDS)).isEqualTo("60");

        toThrow.set(AppException.invalid("email", FieldErrors.REQUIRED));
        Metadata invalid =
                TestService.assertFails(() -> call(new Metadata()), ErrorCode.VALIDATION_ERROR);
        assertThat(invalid.get(MetadataKeys.ERROR_FIELDS)).isEqualTo("email=REQUIRED");
    }

    @Test
    void callsWithoutDeadlineGetACappedContextDeadline() throws Exception {
        AtomicReference<Long> remaining = new AtomicReference<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            start(
                    () -> {
                        remaining.set(
                                io.grpc.Context.current()
                                        .getDeadline()
                                        .timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS));
                        return GetManifestResponse.getDefaultInstance();
                    },
                    new DeadlineCapInterceptor(m -> Duration.ofSeconds(3), scheduler),
                    errors());
            call(new Metadata());
            assertThat(remaining.get()).isBetween(1L, 3000L);
        } finally {
            scheduler.shutdownNow();
        }
    }
}

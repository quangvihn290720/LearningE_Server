package com.learninge.server.edge.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.protobuf.ByteString;
import com.learninge.proto.internal.v1.TokenValidatorGrpc;
import com.learninge.proto.internal.v1.ValidateTokenRequest;
import com.learninge.proto.internal.v1.ValidateTokenResponse;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.security.Digests;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Token → người dùng, hỏi auth và nhớ tối đa 60 giây (D-12). Khóa cache là SHA-256 của token; token
 * thô không rời khỏi edge. Mọi đường thu hồi đều qua edge nên cache bị xóa ngay lúc đó.
 */
public final class TokenAuthenticator {
    private static final String SCHEME = "Bearer ";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long VALIDATE_TIMEOUT_MS = 500;
    private static final int MAX_ENTRIES = 10_000;

    private final InternalChannels channels;
    private final Clock clock;
    private final Cache<ByteString, Identity> cache;
    private final ConcurrentHashMap<String, Set<ByteString>> tokensByUser = new ConcurrentHashMap<>();

    /**
     * Tăng mỗi lần thu hồi: kết quả hỏi từ trước đó không được đưa vào cache nữa.
     */
    private final AtomicLong revocations = new AtomicLong();

    public TokenAuthenticator(InternalChannels channels, Duration maxAge, Clock clock) {
        this.channels = channels;
        this.clock = clock;
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(MAX_ENTRIES)
                        .ticker(() -> clock.millis() * 1_000_000)
                        .expireAfter(
                                Expiry.<ByteString, Identity>creating(
                                        (token, identity) -> {
                                            long untilExpiry = identity.expiresAtMs() - clock.millis();
                                            return Duration.ofMillis(Math.min(maxAge.toMillis(), untilExpiry));
                                        }))
                        .removalListener(
                                (token, identity, cause) -> {
                                    if (identity != null && cause.wasEvicted()) {
                                        forget(identity);
                                    }
                                })
                        .build();
    }

    Identity authenticate(Metadata headers) {
        ByteString tokenSha256 = hashOf(headers.get(MetadataKeys.AUTHORIZATION));
        Identity cached = cache.getIfPresent(tokenSha256);
        if (cached != null && cached.expiresAtMs() > clock.millis()) {
            return cached;
        }
        long before = revocations.get();
        Identity identity = validate(tokenSha256);
        remember(identity, before);
        return identity;
    }

    public void invalidateToken(Identity identity) {
        revocations.incrementAndGet();
        cache.invalidate(identity.tokenSha256());
        forget(identity);
    }

    public void invalidateUser(String userId) {
        revocations.incrementAndGet();
        Set<ByteString> tokens = tokensByUser.remove(userId);
        if (tokens != null) {
            cache.invalidateAll(tokens);
        }
    }

    private void remember(Identity identity, long revocationsBefore) {
        if (revocations.get() != revocationsBefore || identity.expiresAtMs() <= clock.millis()) {
            return;
        }
        tokensByUser
                .computeIfAbsent(identity.userId(), u -> ConcurrentHashMap.newKeySet())
                .add(identity.tokenSha256());
        cache.put(identity.tokenSha256(), identity);
        if (revocations.get() != revocationsBefore) {
            cache.invalidate(identity.tokenSha256());
        }
    }

    private void forget(Identity identity) {
        tokensByUser.computeIfPresent(
                identity.userId(),
                (user, tokens) -> {
                    tokens.remove(identity.tokenSha256());
                    return tokens.isEmpty() ? null : tokens;
                });
    }

    private Identity validate(ByteString tokenSha256) {
        try {
            ValidateTokenResponse response =
                    TokenValidatorGrpc.newBlockingStub(channels.to("auth"))
                            .withDeadlineAfter(VALIDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .validateToken(ValidateTokenRequest.newBuilder().setTokenSha256(tokenSha256).build());
            return new Identity(
                    response.getUserId(), response.getUserHmac(), response.getExpiresAtMs(), tokenSha256);
        } catch (StatusRuntimeException e) {
            Metadata trailers = e.getTrailers();
            boolean rejectedByAuth =
                    e.getStatus().getCode() == Status.Code.UNAUTHENTICATED
                            && trailers != null
                            && trailers.containsKey(MetadataKeys.ERROR_CODE);
            // auth ngừng hoặc sai khóa nội bộ KHÔNG được thành UNAUTHENTICATED: app sẽ xóa token.
            throw AppException.of(
                    rejectedByAuth ? ErrorCode.UNAUTHENTICATED : ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static ByteString hashOf(String authorization) {
        if (authorization == null || !authorization.startsWith(SCHEME)) {
            throw AppException.of(ErrorCode.UNAUTHENTICATED);
        }
        String token = authorization.substring(SCHEME.length());
        if (!TOKEN.matcher(token).matches()) {
            throw AppException.of(ErrorCode.UNAUTHENTICATED);
        }
        return ByteString.copyFrom(Digests.sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }
}

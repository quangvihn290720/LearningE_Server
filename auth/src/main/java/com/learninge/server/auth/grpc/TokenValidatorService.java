package com.learninge.server.auth.grpc;

import com.google.protobuf.ByteString;
import com.learninge.proto.internal.v1.TokenValidatorGrpc;
import com.learninge.proto.internal.v1.ValidateTokenRequest;
import com.learninge.proto.internal.v1.ValidateTokenResponse;
import com.learninge.server.auth.service.SessionService;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.common.security.UserPseudonym;
import io.grpc.stub.StreamObserver;

/**
 * Edge hỏi token còn hiệu lực không. Mọi lý do hỏng dùng chung một mã lỗi.
 */
public final class TokenValidatorService extends TokenValidatorGrpc.TokenValidatorImplBase {
    private static final int SHA256_BYTES = 32;

    private final SessionService sessions;
    private final UserPseudonym pseudonym;

    public TokenValidatorService(SessionService sessions, UserPseudonym pseudonym) {
        this.sessions = sessions;
        this.pseudonym = pseudonym;
    }

    @Override
    public void validateToken(
            ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> observer) {
        var token =
                sessions
                        .validate(requireSha256(request.getTokenSha256()))
                        .orElseThrow(() -> AppException.of(ErrorCode.UNAUTHENTICATED));
        Unary.reply(
                observer,
                ValidateTokenResponse.newBuilder()
                        .setUserId(token.userUuid())
                        .setExpiresAtMs(token.expiresAt())
                        .setUserHmac(pseudonym.of(token.userUuid()))
                        .build());
    }

    static byte[] requireSha256(ByteString value) {
        if (value.size() != SHA256_BYTES) {
            throw AppException.invalid("token_sha256", FieldErrors.INVALID_FORMAT);
        }
        return value.toByteArray();
    }
}

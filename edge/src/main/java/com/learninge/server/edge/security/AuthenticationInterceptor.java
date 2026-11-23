package com.learninge.server.edge.security;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.grpc.CallInfo;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.GrpcErrors;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Mọi RPC của app cần token, trừ danh sách công khai ở {@link AppMethods}.
 */
public final class AuthenticationInterceptor implements ServerInterceptor {
    private final TokenAuthenticator tokens;

    public AuthenticationInterceptor(TokenAuthenticator tokens) {
        this.tokens = tokens;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!AppMethods.needsToken(call)) {
            return next.startCall(call, headers);
        }
        Identity identity;
        try {
            identity = tokens.authenticate(headers);
        } catch (AppException e) {
            return GrpcErrors.reject(call, e);
        }
        CallInfo.KEY.get().userHmac(identity.userHmac());
        Context context =
                Context.current()
                        .withValue(Identity.KEY, identity)
                        .withValue(CallerContext.USER_ID, identity.userId());
        return Contexts.interceptCall(context, call, headers, next);
    }
}

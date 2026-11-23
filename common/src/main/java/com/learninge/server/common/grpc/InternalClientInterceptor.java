package com.learninge.server.common.grpc;

import com.learninge.server.common.config.Secret;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Gắn khóa nội bộ, {@code x-request-id} và {@code x-user-id} (nếu có) vào mọi lời gọi nội bộ.
 */
public final class InternalClientInterceptor implements ClientInterceptor {
    private final Secret internalToken;

    public InternalClientInterceptor(Secret internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        CallInfo info = CallInfo.KEY.get();
        String userId = CallerContext.USER_ID.get();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                headers.put(MetadataKeys.INTERNAL_AUTH, internalToken.reveal());
                if (info != null) {
                    headers.put(MetadataKeys.REQUEST_ID, info.requestId());
                }
                if (userId != null) {
                    headers.put(MetadataKeys.USER_ID, userId);
                }
                super.start(listener, headers);
            }
        };
    }
}

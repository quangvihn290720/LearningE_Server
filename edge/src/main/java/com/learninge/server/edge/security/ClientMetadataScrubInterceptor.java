package com.learninge.server.edge.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Chạy đầu tiên: xóa mọi metadata danh tính nội bộ do client gửi ({@code x-user-id}, {@code
 * x-user-id-bin}, {@code x-internal-*}) trước khi bất cứ mã nào đọc tới (TM-33).
 */
public final class ClientMetadataScrubInterceptor implements ServerInterceptor {
    private static final String BINARY = Metadata.BINARY_HEADER_SUFFIX;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        for (String key : headers.keys()) {
            if (key.startsWith("x-user-id") || key.startsWith("x-internal-")) {
                headers.discardAll(key(key));
            }
        }
        return next.startCall(call, headers);
    }

    private static Metadata.Key<?> key(String name) {
        return name.endsWith(BINARY)
                ? Metadata.Key.of(name, Metadata.BINARY_BYTE_MARSHALLER)
                : Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}

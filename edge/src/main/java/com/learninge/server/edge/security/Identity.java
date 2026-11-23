package com.learninge.server.edge.security;

import com.google.protobuf.ByteString;
import io.grpc.Context;

/**
 * Danh tính đã xác thực của lời gọi. Không bao giờ chứa token thô.
 */
public record Identity(String userId, String userHmac, long expiresAtMs, ByteString tokenSha256) {
    public static final Context.Key<Identity> KEY = Context.key("learninge-identity");
}

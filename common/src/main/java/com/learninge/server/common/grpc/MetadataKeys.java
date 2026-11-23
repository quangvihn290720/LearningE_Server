package com.learninge.server.common.grpc;

import io.grpc.Metadata;

/**
 * Mọi khóa metadata/trailer của hệ thống (SYSTEM-BLUEPRINT §Metadata gRPC).
 */
public final class MetadataKeys {
    public static final Metadata.Key<String> AUTHORIZATION = ascii("authorization");
    public static final Metadata.Key<String> REQUEST_ID = ascii("x-request-id");
    public static final Metadata.Key<String> CLIENT_VERSION = ascii("x-client-version");
    public static final Metadata.Key<String> USER_ID = ascii("x-user-id");
    public static final Metadata.Key<String> INTERNAL_AUTH = ascii("x-internal-auth");
    public static final Metadata.Key<String> SERVER_VERSION = ascii("x-server-version");
    public static final Metadata.Key<String> SERVER_TIME_MS = ascii("x-server-time-ms");
    public static final Metadata.Key<String> ERROR_CODE = ascii("error-code");
    public static final Metadata.Key<String> ERROR_FIELDS = ascii("error-fields");
    public static final Metadata.Key<String> RETRY_AFTER_SECONDS = ascii("retry-after-seconds");

    private MetadataKeys() {
    }

    private static Metadata.Key<String> ascii(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}

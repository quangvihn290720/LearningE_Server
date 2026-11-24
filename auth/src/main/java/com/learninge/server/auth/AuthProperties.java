package com.learninge.server.auth;

import com.learninge.server.common.config.Secret;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình riêng của auth (tiền tố {@code learninge.auth}).
 */
@ConfigurationProperties("learninge.auth")
public record AuthProperties(
        Path dbPath, Secret serverKey, Secret pseudonymKey, int tokenTtlDays, int tokenMaxAgeDays, int maxConcurrentHashes) {

    public Duration tokenMaxAge() {
        return Duration.ofDays(tokenMaxAgeDays);
    }

    public Duration tokenTtl() {
        return Duration.ofDays(tokenTtlDays);
    }
}

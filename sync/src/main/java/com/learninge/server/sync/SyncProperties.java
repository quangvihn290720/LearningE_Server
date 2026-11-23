package com.learninge.server.sync;

import com.learninge.server.common.config.Secret;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình riêng của sync (tiền tố {@code learninge.sync}).
 */
@ConfigurationProperties("learninge.sync")
public record SyncProperties(Path dbPath, Secret pseudonymKey) {
}

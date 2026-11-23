package com.learninge.server.edge;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình riêng của edge (tiền tố {@code learninge.edge}).
 */
@ConfigurationProperties("learninge.edge")
public record EdgeProperties(
        boolean trustProxy,
        List<String> trustedProxies,
        int minClient,
        int tokenCacheSecs,
        boolean reflection) {
}

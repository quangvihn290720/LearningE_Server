package com.learninge.server.common.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình dùng chung mọi dịch vụ (tiền tố {@code learninge}).
 *
 * @param services          địa chỉ các dịch vụ nội bộ, vd {@code auth -> auth:50052}
 * @param backgroundEnabled tắt relay và việc nền khi chạy lệnh {@code --ops}
 */
@ConfigurationProperties("learninge")
public record LearningeProperties(
        @DefaultValue("dev") AppEnv env,
        Secret internalToken,
        @DefaultValue("0.1.0") String serverVersion,
        @DefaultValue("32") int grpcThreads,
        @DefaultValue Map<String, String> services,
        @DefaultValue("true") boolean backgroundEnabled) {
}

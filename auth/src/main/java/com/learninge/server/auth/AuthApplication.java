package com.learninge.server.auth;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Dịch vụ tài khoản (cổng 50052). Không quét component: mọi bean khai báo ở AuthServiceConfig.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AuthServiceConfig.class)
public class AuthApplication {
    public static final String NAME = "auth";

    public static void main(String[] args) {
        ServiceLauncher.main(AuthApplication.class, NAME, args);
    }
}

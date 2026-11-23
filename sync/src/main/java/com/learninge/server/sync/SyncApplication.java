package com.learninge.server.sync;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Dịch vụ đồng bộ (cổng 50053).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SyncServiceConfig.class)
public class SyncApplication {
    public static final String NAME = "sync";

    public static void main(String[] args) {
        ServiceLauncher.main(SyncApplication.class, NAME, args);
    }
}

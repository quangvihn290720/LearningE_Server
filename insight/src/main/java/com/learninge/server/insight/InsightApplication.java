package com.learninge.server.insight;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Dịch vụ nhật ký và số liệu đã khử định danh (cổng 50056). App không gọi dịch vụ này.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(InsightServiceConfig.class)
public class InsightApplication {
    public static final String NAME = "insight";

    public static void main(String[] args) {
        ServiceLauncher.main(InsightApplication.class, NAME, args);
    }
}

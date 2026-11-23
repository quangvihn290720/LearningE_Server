package com.learninge.server.content;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Dịch vụ nội dung học (cổng 50054).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ContentServiceConfig.class)
public class ContentApplication {
    public static final String NAME = "content";

    public static void main(String[] args) {
        ServiceLauncher.main(ContentApplication.class, NAME, args);
    }
}

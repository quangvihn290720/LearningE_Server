package com.learninge.server.common.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Điểm khởi động chung. Mỗi dịch vụ có tệp cấu hình mang tên mình ({@code auth.yml}) đè lên {@code
 * learninge-defaults.yml}, nhờ vậy 6 dịch vụ cùng chạy được trong một JVM.
 */
public final class ServiceLauncher {
    private static final Logger log = LoggerFactory.getLogger(ServiceLauncher.class);

    /**
     * Lệnh mà kết quả chính là thứ in ra stdout: tắt log trên console để chuyển hướng được vào tệp.
     */
    private static final Set<String> PRINTING = Set.of("outbox-list", "report");

    private ServiceLauncher() {
    }

    public static void main(Class<?> application, String service, String[] args) {
        ConfigurableApplicationContext context = start(application, service, builder -> {
        }, args);
        String ops = opsName(args);
        if (ops != null) {
            System.exit(runOps(context, ops));
        }
    }

    public static ConfigurableApplicationContext start(
            Class<?> application,
            String service,
            Consumer<SpringApplicationBuilder> customizer,
            String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(application);
        customizer.accept(builder);
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--spring.config.name=learninge-defaults," + service);
        all.add("--spring.application.name=" + service);
        if (opsName(args) != null) {
            // Lệnh vận hành không mở cổng và không chạy việc nền.
            all.add("--spring.grpc.server.enabled=false");
            all.add("--spring.main.web-application-type=none");
            all.add("--learninge.background-enabled=false");
            if (PRINTING.contains(opsName(args))) {
                all.add("--logging.threshold.console=OFF");
            }
        }
        return builder.run(all.toArray(String[]::new));
    }

    private static String opsName(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--ops=")) {
                return arg.substring("--ops=".length());
            }
        }
        return null;
    }

    private static int runOps(ConfigurableApplicationContext context, String name) {
        try (context) {
            OpsCommand command =
                    context.getBeansOfType(OpsCommand.class).values().stream()
                            .filter(c -> c.name().equals(name))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Không có lệnh --ops=" + name));
            command.run(context.getBean(ApplicationArguments.class));
            return 0;
        } catch (Exception e) {
            log.error("Lệnh --ops={} thất bại", name, e);
            System.err.println("Lệnh --ops=" + name + " thất bại: " + e.getMessage());
            return 1;
        }
    }
}

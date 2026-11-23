package com.learninge.server.allinone;

import com.learninge.server.auth.AuthApplication;
import com.learninge.server.common.ops.ServiceLauncher;
import com.learninge.server.content.ContentApplication;
import com.learninge.server.edge.EdgeApplication;
import com.learninge.server.gamification.GamificationApplication;
import com.learninge.server.insight.InsightApplication;
import com.learninge.server.sync.SyncApplication;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * Khởi các dịch vụ theo thứ tự bên nhận trước, bên gọi sau; một dịch vụ lỗi thì dừng tất cả.
 */
public final class AllInOne implements AutoCloseable {
    /**
     * Bên nhận sự kiện trước, edge cuối cùng.
     */
    static final List<String> ALL =
            List.of("insight", "gamification", "content", "sync", "auth", "edge");

    private static final Map<String, Class<?>> APPLICATIONS =
            Map.of(
                    "edge",
                    EdgeApplication.class,
                    "auth",
                    AuthApplication.class,
                    "sync",
                    SyncApplication.class,
                    "content",
                    ContentApplication.class,
                    "gamification",
                    GamificationApplication.class,
                    "insight",
                    InsightApplication.class);

    private static final Map<String, Integer> PORTS =
            Map.of(
                    "edge",
                    50051,
                    "auth",
                    50052,
                    "sync",
                    50053,
                    "content",
                    50054,
                    "gamification",
                    50055,
                    "insight",
                    50056);

    private final Map<String, ConfigurableApplicationContext> contexts = new LinkedHashMap<>();

    private AllInOne() {
    }

    public static AllInOne start(List<String> services, boolean randomPorts, String... extra) {
        for (String service : services) {
            if (!APPLICATIONS.containsKey(service)) {
                throw new IllegalArgumentException("Không có dịch vụ " + service);
            }
        }
        AllInOne running = new AllInOne();
        List<String> shared = sharedArguments(extra);
        try {
            for (String service : ALL) {
                if (services.contains(service)) {
                    running.startOne(service, randomPorts, shared);
                }
            }
            return running;
        } catch (RuntimeException e) {
            running.close();
            throw e;
        }
    }

    private void startOne(String service, boolean randomPorts, List<String> shared) {
        List<String> args = new ArrayList<>(shared);
        args.add("--spring.main.web-application-type=none");
        args.add("--spring.grpc.server.port=" + (randomPorts ? 0 : PORTS.get(service)));
        if (!service.equals("edge")) {
            // Trên LAN chỉ edge được phép nghe ngoài loopback (TM-13).
            args.add("--spring.grpc.server.address=127.0.0.1");
        }
        ports()
                .forEach((name, port) -> args.add("--learninge.services." + name + "=127.0.0.1:" + port));
        contexts.put(
                service,
                ServiceLauncher.start(
                        APPLICATIONS.get(service), service, builder -> {
                        }, args.toArray(String[]::new)));
    }

    /**
     * Khóa dùng chung sinh tạm khi dev chưa cấu hình; prod vẫn bắt buộc đặt (kiểm ở từng dịch vụ).
     */
    private static List<String> sharedArguments(String[] extra) {
        List<String> args = new ArrayList<>(List.of(extra));
        boolean prod = "prod".equalsIgnoreCase(System.getenv("APP_ENV"));
        if (!prod) {
            addIfMissing(args, "learninge.internal-token", "LEARNINGE_INTERNAL_TOKEN");
            addIfMissing(args, "learninge.auth.server-key", "LEARNINGE_SERVER_KEY");
            String pseudonym =
                    addIfMissing(args, "learninge.auth.pseudonym-key", "LEARNINGE_PSEUDONYM_KEY");
            if (pseudonym != null) {
                args.add("--learninge.sync.pseudonym-key=" + pseudonym);
            }
        }
        return args;
    }

    private static String addIfMissing(List<String> args, String property, String environment) {
        String prefix = "--" + property + "=";
        if (System.getenv(environment) != null || args.stream().anyMatch(a -> a.startsWith(prefix))) {
            return null;
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String value = Base64.getEncoder().encodeToString(key);
        args.add(prefix + value);
        return value;
    }

    public Map<String, Integer> ports() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        contexts.forEach(
                (name, context) -> ports.put(name, context.getBean(GrpcServerLifecycle.class).getPort()));
        return ports;
    }

    public <T> T bean(String service, Class<T> type) {
        return contexts.get(service).getBean(type);
    }

    public void stop(String service) {
        contexts.remove(service).close();
    }

    /**
     * Dừng ngược thứ tự khởi động: edge ngừng nhận trước.
     */
    @Override
    public void close() {
        List<String> names = new ArrayList<>(contexts.keySet());
        names.reversed().forEach(this::stop);
    }
}

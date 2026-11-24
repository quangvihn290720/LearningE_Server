package com.learninge.server.allinone;

import com.learninge.server.common.ops.ServiceLauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chế độ gộp: 6 dịch vụ trong một JVM, cùng mã với chế độ tách. Dùng khi phát triển hoặc khi máy
 * demo yếu RAM. {@code --all} (mặc định) hoặc {@code --only=edge,auth}. Lệnh vận hành:
 * {@code --only=<một dịch vụ> --ops=…}.
 */
public final class AllInOneApplication {
    private static final Logger log = LoggerFactory.getLogger(AllInOneApplication.class);
    private static final String ONLY = "--only=";

    private AllInOneApplication() {
    }

    public static void main(String[] args) {
        if (Arrays.stream(args).anyMatch(a -> a.startsWith("--ops="))) {
            List<String> services = services(args);
            if (services.size() != 1) {
                throw new IllegalArgumentException("Lệnh --ops cần --only=<một dịch vụ>");
            }
            String service = services.getFirst();
            List<String> all = new ArrayList<>(List.of(extra(args)));
            all.addAll(AllInOne.mongoArguments(service));
            ServiceLauncher.main(AllInOne.application(service), service, all.toArray(String[]::new));
            return;
        }
        try {
            AllInOne running = AllInOne.start(services(args), false, extra(args));
            log.info("Đã chạy: {}", running.ports());
        } catch (RuntimeException e) {
            log.error("Không khởi động được", e);
            System.exit(1);
        }
    }

    private static List<String> services(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(ONLY)) {
                return List.of(arg.substring(ONLY.length()).split(","));
            }
        }
        return AllInOne.ALL;
    }

    private static String[] extra(String[] args) {
        return Arrays.stream(args)
                .filter(a -> !a.equals("--all") && !a.startsWith(ONLY))
                .toArray(String[]::new);
    }
}

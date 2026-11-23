package com.learninge.server.common.ops;

import org.springframework.boot.ApplicationArguments;

/**
 * Lệnh vận hành chạy bằng chính jar của dịch vụ: {@code java -jar auth.jar --ops=backup --out=…}.
 */
public interface OpsCommand {
    String name();

    void run(ApplicationArguments arguments) throws Exception;

    /**
     * Tham số bắt buộc dạng {@code --name=value}.
     */
    static String required(ApplicationArguments arguments, String name) {
        var values = arguments.getOptionValues(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Thiếu tham số --" + name);
        }
        return values.getFirst();
    }
}

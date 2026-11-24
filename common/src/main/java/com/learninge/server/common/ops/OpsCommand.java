package com.learninge.server.common.ops;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.ApplicationArguments;

/**
 * Lệnh vận hành chạy bằng chính jar của dịch vụ: {@code java -jar auth.jar --ops=backup --out=…}.
 */
public interface OpsCommand {
    String name();

    void run(ApplicationArguments arguments) throws Exception;

    /**
     * Nơi in kết quả; luôn UTF-8 dù máy dùng bảng mã khác.
     */
    static PrintStream stdout() {
        return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    }

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

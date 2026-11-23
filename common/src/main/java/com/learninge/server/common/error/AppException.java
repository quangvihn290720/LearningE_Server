package com.learninge.server.common.error;

import io.grpc.Status;

import java.util.Map;

/**
 * Lỗi nghiệp vụ có chủ đích. Interceptor trong cùng đổi nó thành {@link Status} + trailer.
 *
 * <p>Không mang stack trace: đây là kết quả bình thường của một lời gọi, không phải sự cố.
 */
public final class AppException extends RuntimeException {
    private final ErrorCode code;
    private final Status.Code status;
    private final long retryAfterSeconds;
    private final transient Map<String, String> fields;

    private AppException(
            ErrorCode code,
            Status.Code status,
            String message,
            long retryAfterSeconds,
            Map<String, String> fields) {
        super(message, null, false, false);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.fields = fields;
    }

    public static AppException of(ErrorCode code) {
        return new AppException(code, code.status(), code.defaultMessage(), 0, Map.of());
    }

    public static AppException of(ErrorCode code, String message) {
        return new AppException(code, code.status(), message, 0, Map.of());
    }

    /**
     * Cùng mã nghiệp vụ nhưng mã gRPC khác, vd sai mật khẩu khi đã đăng nhập (EP-07, EP-10).
     */
    public static AppException of(ErrorCode code, Status.Code status, String message) {
        return new AppException(code, status, message, 0, Map.of());
    }

    public static AppException retryAfter(ErrorCode code, long seconds) {
        String message = code.defaultMessage() + ". Thử lại sau " + seconds + " giây";
        return new AppException(code, code.status(), message, seconds, Map.of());
    }

    public static AppException invalid(Map<String, String> fields) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return new AppException(code, code.status(), code.defaultMessage(), 0, Map.copyOf(fields));
    }

    public static AppException invalid(String field, String reason) {
        return invalid(Map.of(field, reason));
    }

    /**
     * Dịch vụ đang bận (hàng đợi băm, SQLite bận): app thử lại sau 5 giây.
     */
    public static AppException busy() {
        ErrorCode code = ErrorCode.SERVICE_UNAVAILABLE;
        return new AppException(code, code.status(), "Máy chủ đang bận", 5, Map.of());
    }

    public ErrorCode code() {
        return code;
    }

    public Status.Code status() {
        return status;
    }

    /**
     * 0 nghĩa là không gửi trailer {@code retry-after-seconds}.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, String> fields() {
        return fields;
    }
}

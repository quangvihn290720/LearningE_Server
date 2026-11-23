package com.learninge.server.common.mongo;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.mongodb.MongoException;
import com.mongodb.MongoServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongo ngừng hoặc không kết nối được → {@code UNAVAILABLE} (bên gọi thử lại). Validator từ chối →
 * {@code INVALID_ARGUMENT}: gửi lại cũng vô ích, để outbox chuyển sự kiện sang "dead" thay vì kẹt
 * hàng đợi.
 */
public final class MongoErrors {
    private static final Logger log = LoggerFactory.getLogger(MongoErrors.class);
    private static final int DUPLICATE_KEY = 11000;
    private static final int VALIDATION_FAILURE = 121;

    private MongoErrors() {
    }

    public static AppException translate(RuntimeException error) {
        if (error instanceof MongoException mongo && mongo.getCode() == VALIDATION_FAILURE) {
            log.error("Validator MongoDB từ chối tài liệu", error);
            return AppException.of(ErrorCode.VALIDATION_ERROR);
        }
        boolean unreachable =
                error instanceof MongoException && !(error instanceof MongoServerException);
        return unreachable ? AppException.of(ErrorCode.SERVICE_UNAVAILABLE) : null;
    }

    public static boolean isDuplicateKey(MongoException error) {
        return error.getCode() == DUPLICATE_KEY;
    }
}

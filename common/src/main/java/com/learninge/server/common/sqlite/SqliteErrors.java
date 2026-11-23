package com.learninge.server.common.sqlite;

import com.learninge.server.common.error.AppException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Nhận diện lỗi SQLite cần đổi sang mã nghiệp vụ.
 */
public final class SqliteErrors {
    private SqliteErrors() {
    }

    /**
     * File đang bận quá {@code busy_timeout} hoặc hết kết nối → {@code UNAVAILABLE}; khác → null.
     */
    public static AppException translate(RuntimeException error) {
        if (error instanceof CannotCreateTransactionException) {
            return AppException.busy();
        }
        SQLiteException sqlite = find(error);
        if (sqlite == null) {
            return null;
        }
        SQLiteErrorCode code = sqlite.getResultCode();
        boolean busy = (code.code & 0xff) == SQLiteErrorCode.SQLITE_BUSY.code;
        return busy ? AppException.busy() : null;
    }

    /**
     * {@code column} dạng {@code users.email}, đúng như SQLite ghi trong thông điệp lỗi.
     */
    public static boolean isUniqueViolation(RuntimeException error, String column) {
        SQLiteException sqlite = find(error);
        return sqlite != null
                && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                && sqlite.getMessage().contains(column);
    }

    private static SQLiteException find(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException sqlite) {
                return sqlite;
            }
        }
        return null;
    }
}

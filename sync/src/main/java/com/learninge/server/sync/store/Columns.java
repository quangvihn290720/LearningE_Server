package com.learninge.server.sync.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Tiện ích đọc cột cho phép NULL vào trường {@code optional} của proto.
 */
final class Columns {
    private Columns() {
    }

    static void text(ResultSet rs, String column, Consumer<String> setter) throws SQLException {
        String value = rs.getString(column);
        if (value != null) {
            setter.accept(value);
        }
    }

    static void number(ResultSet rs, String column, Consumer<Long> setter) throws SQLException {
        long value = rs.getLong(column);
        if (!rs.wasNull()) {
            setter.accept(value);
        }
    }
}

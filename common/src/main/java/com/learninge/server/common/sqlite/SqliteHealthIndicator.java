package com.learninge.server.common.sqlite;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;

/**
 * SQLite không đọc được thì dịch vụ báo {@code NOT_SERVING} (lõi của auth và sync).
 */
public final class SqliteHealthIndicator implements HealthIndicator {
    private final SqliteDatabase database;

    public SqliteHealthIndicator(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public Health health() {
        try {
            database.reader().sql("SELECT 1 FROM sqlite_master LIMIT 1").query(Integer.class).optional();
            return Health.up().build();
        } catch (DataAccessException e) {
            return Health.down().build();
        }
    }
}

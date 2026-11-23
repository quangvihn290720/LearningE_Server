package com.learninge.server.auth.store;

import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.auth.domain.LockState;
import com.learninge.server.common.sqlite.SqliteDatabase;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bộ đếm khóa cho email không tồn tại, khóa theo HMAC của email (không lưu email rõ).
 */
public final class AttemptStore {
    private static final String FINGERPRINT = "server_key_fingerprint";

    private final SqliteDatabase database;

    public AttemptStore(SqliteDatabase database) {
        this.database = database;
    }

    public LockState find(String subject, LockScope scope) {
        return find(database.reader(), subject, scope);
    }

    public LockState lock(String subject, LockScope scope) {
        return find(database.writer(), subject, scope);
    }

    public void save(String subject, LockScope scope, LockState state) {
        database
                .writer()
                .sql(
                        """
                                INSERT INTO auth_attempts (subject, scope, failed_count, lock_count, locked_until,
                                                           last_fail_at)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT (subject, scope) DO UPDATE SET
                                  failed_count = excluded.failed_count, lock_count = excluded.lock_count,
                                  locked_until = excluded.locked_until, last_fail_at = excluded.last_fail_at
                                """)
                .params(
                        subject,
                        scope.name(),
                        state.failedCount(),
                        state.lockCount(),
                        state.lockedUntil(),
                        state.lastFailAt())
                .update();
    }

    public void delete(String subject) {
        database.writer().sql("DELETE FROM auth_attempts WHERE subject = ?").param(subject).update();
    }

    public void deleteAll() {
        database.writer().sql("DELETE FROM auth_attempts").update();
    }

    public int deleteInactiveBefore(long threshold) {
        return database
                .writer()
                .sql("DELETE FROM auth_attempts WHERE max(coalesce(locked_until, 0), last_fail_at) < ?")
                .param(threshold)
                .update();
    }

    public Optional<String> keyFingerprint() {
        return database
                .writer()
                .sql("SELECT value FROM server_meta WHERE key = ?")
                .param(FINGERPRINT)
                .query(String.class)
                .optional();
    }

    public void saveKeyFingerprint(String value) {
        database
                .writer()
                .sql(
                        """
                                INSERT INTO server_meta (key, value) VALUES (?, ?)
                                ON CONFLICT (key) DO UPDATE SET value = excluded.value
                                """)
                .params(FINGERPRINT, value)
                .update();
    }

    private static LockState find(JdbcClient client, String subject, LockScope scope) {
        return client
                .sql(
                        """
                                SELECT failed_count, lock_count, locked_until, last_fail_at FROM auth_attempts
                                WHERE subject = ? AND scope = ?
                                """)
                .params(subject, scope.name())
                .query(
                        (rs, i) ->
                                new LockState(
                                        rs.getInt("failed_count"),
                                        rs.getInt("lock_count"),
                                        rs.getLong("locked_until"),
                                        rs.getLong("last_fail_at")))
                .optional()
                .orElse(LockState.CLEAR);
    }
}

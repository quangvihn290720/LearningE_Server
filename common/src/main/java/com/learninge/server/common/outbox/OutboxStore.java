package com.learninge.server.common.outbox;

import com.learninge.server.common.sqlite.SqliteDatabase;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL của bảng {@code outbox}; bảng này giống hệt nhau ở auth.db và sync.db.
 */
public final class OutboxStore {
    private final SqliteDatabase database;

    public OutboxStore(SqliteDatabase database) {
        this.database = database;
    }

    /**
     * Gọi bên trong giao dịch nghiệp vụ.
     */
    void insert(
            String eventId,
            Destination destination,
            EventType type,
            String aggregateId,
            byte[] payload,
            long now) {
        writer()
                .sql(
                        """
                                INSERT INTO outbox (event_id, destination, aggregate, aggregate_id, type, payload,
                                                    created_at, next_attempt_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                .params(
                        eventId, destination.id(), type.aggregate(), aggregateId, type.id(), payload, now, now)
                .update();
    }

    List<OutboxRow> pending(Destination destination, int limit) {
        return database
                .reader()
                .sql(
                        """
                                SELECT id, event_id, payload, attempts, next_attempt_at FROM outbox
                                WHERE destination = ? AND published_at IS NULL AND dead_at IS NULL
                                ORDER BY id LIMIT ?
                                """)
                .params(destination.id(), limit)
                .query(
                        (rs, i) ->
                                new OutboxRow(
                                        rs.getLong("id"),
                                        rs.getString("event_id"),
                                        rs.getBytes("payload"),
                                        rs.getInt("attempts"),
                                        rs.getLong("next_attempt_at")))
                .list();
    }

    long countPending(Destination destination) {
        return database
                .reader()
                .sql(
                        """
                                SELECT COUNT(*) FROM outbox
                                WHERE destination = ? AND published_at IS NULL AND dead_at IS NULL
                                """)
                .param(destination.id())
                .query(Long.class)
                .single();
    }

    void markPublished(List<OutboxRow> rows, long now) {
        update(rows, "UPDATE outbox SET published_at = ? WHERE id = ?", now);
    }

    void markFailed(List<OutboxRow> rows, long nextAttemptAt, String error) {
        database.inWriteTx(
                () -> {
                    for (OutboxRow row : rows) {
                        writer()
                                .sql(
                                        """
                                                UPDATE outbox SET attempts = attempts + 1, next_attempt_at = ?, last_error = ?
                                                WHERE id = ?
                                                """)
                                .params(nextAttemptAt, error, row.id())
                                .update();
                    }
                });
    }

    void markDead(OutboxRow row, long now, String error) {
        writer()
                .sql("UPDATE outbox SET attempts = attempts + 1, dead_at = ?, last_error = ? WHERE id = ?")
                .params(now, error, row.id())
                .update();
    }

    /**
     * Đích đã sống lại: thử ngay, trừ dòng đang bị từ chối vĩnh viễn (phải đếm tới lúc "chết").
     */
    void resetBackoff(Destination destination, long now) {
        writer()
                .sql(
                        """
                                UPDATE outbox SET attempts = 0, next_attempt_at = ?
                                WHERE destination = ? AND published_at IS NULL AND dead_at IS NULL AND attempts > 0
                                  AND last_error NOT IN ('INVALID_ARGUMENT', 'FAILED_PRECONDITION')
                                """)
                .params(now, destination.id())
                .update();
    }

    int deletePublishedBefore(long threshold) {
        return writer().sql("DELETE FROM outbox WHERE published_at < ?").param(threshold).update();
    }

    int requeue(String eventId, long now) {
        return writer()
                .sql(
                        """
                                UPDATE outbox SET dead_at = NULL, attempts = 0, last_error = NULL, next_attempt_at = ?
                                WHERE event_id = ? AND published_at IS NULL
                                """)
                .params(now, eventId)
                .update();
    }

    private void update(List<OutboxRow> rows, String sql, long now) {
        database.inWriteTx(
                () -> {
                    for (OutboxRow row : rows) {
                        writer().sql(sql).params(now, row.id()).update();
                    }
                });
    }

    private JdbcClient writer() {
        return database.writer();
    }
}

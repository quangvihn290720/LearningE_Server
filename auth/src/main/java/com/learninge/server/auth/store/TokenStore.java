package com.learninge.server.auth.store;

import com.learninge.server.common.sqlite.SqliteDatabase;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL của {@code auth_tokens}. Chỉ lưu SHA-256 của token.
 */
public final class TokenStore {
    public record ActiveToken(
            long id, String userUuid, long createdAt, long expiresAt, long lastUsedAt) {
        public ActiveToken expiringAt(long newExpiresAt) {
            return new ActiveToken(id, userUuid, createdAt, newExpiresAt, lastUsedAt);
        }
    }

    /**
     * Một phiên đang mở, để người dùng nhận ra máy của mình. {@code hash} chỉ dùng để so với máy đang gọi.
     */
    public record DeviceSession(
            long id, String hash, String deviceName, long createdAt, long lastUsedAt, long expiresAt) {
    }

    private final SqliteDatabase database;

    public TokenStore(SqliteDatabase database) {
        this.database = database;
    }

    public void insert(long userId, String hash, String deviceName, long now, long expiresAt) {
        database
                .writer()
                .sql(
                        """
                                INSERT INTO auth_tokens (user_id, token_hash, device_name, created_at, expires_at)
                                VALUES (?, ?, ?, ?, ?)
                                """)
                .params(userId, hash, deviceName.isEmpty() ? null : deviceName, now, expiresAt)
                .update();
    }

    public Optional<ActiveToken> findActive(String hash, long now) {
        return database
                .reader()
                .sql(
                        """
                                SELECT t.id, u.uuid, t.created_at, t.expires_at, t.last_used_at
                                FROM auth_tokens t JOIN users u ON u.id = t.user_id
                                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > ?
                                """)
                .params(hash, now)
                .query(
                        (rs, i) ->
                                new ActiveToken(
                                        rs.getLong("id"),
                                        rs.getString("uuid"),
                                        rs.getLong("created_at"),
                                        rs.getLong("expires_at"),
                                        rs.getLong("last_used_at")))
                .optional();
    }

    public void touch(long id, long now, long expiresAt) {
        database
                .writer()
                .sql("UPDATE auth_tokens SET last_used_at = ?, expires_at = ? WHERE id = ? AND revoked_at IS NULL")
                .params(now, expiresAt, id)
                .update();
    }

    public List<DeviceSession> activeOf(String userUuid, long now) {
        return database
                .reader()
                .sql(
                        """
                                SELECT t.id, t.token_hash, t.device_name, t.created_at, t.last_used_at, t.expires_at
                                FROM auth_tokens t JOIN users u ON u.id = t.user_id
                                WHERE u.uuid = ? AND t.revoked_at IS NULL AND t.expires_at > ?
                                ORDER BY max(t.created_at, coalesce(t.last_used_at, 0)) DESC, t.id DESC
                                """)
                .params(userUuid, now)
                .query(
                        (rs, i) ->
                                new DeviceSession(
                                        rs.getLong("id"),
                                        rs.getString("token_hash"),
                                        Objects.requireNonNullElse(rs.getString("device_name"), ""),
                                        rs.getLong("created_at"),
                                        rs.getLong("last_used_at"),
                                        rs.getLong("expires_at")))
                .list();
    }

    /**
     * Chỉ thu hồi được phiên của chính người gọi.
     */
    public int revokeOne(String userUuid, long id, long now) {
        return database
                .writer()
                .sql(
                        """
                                UPDATE auth_tokens SET revoked_at = ?
                                WHERE id = ? AND revoked_at IS NULL
                                  AND user_id = (SELECT id FROM users WHERE uuid = ?)
                                """)
                .params(now, id, userUuid)
                .update();
    }

    public int revokeOthers(String userUuid, String currentHash, long now) {
        return database
                .writer()
                .sql(
                        """
                                UPDATE auth_tokens SET revoked_at = ?
                                WHERE token_hash <> ? AND revoked_at IS NULL AND expires_at > ?
                                  AND user_id = (SELECT id FROM users WHERE uuid = ?)
                                """)
                .params(now, currentHash, now, userUuid)
                .update();
    }

    public void revoke(String hash, long now) {
        database
                .writer()
                .sql("UPDATE auth_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
                .params(now, hash)
                .update();
    }

    public void revokeAll(long userId, long now) {
        database
                .writer()
                .sql("UPDATE auth_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .params(now, userId)
                .update();
    }

    public int revokeAllUsers(long now) {
        return database
                .writer()
                .sql("UPDATE auth_tokens SET revoked_at = ? WHERE revoked_at IS NULL")
                .param(now)
                .update();
    }

    public int deleteEndedBefore(long threshold) {
        return database
                .writer()
                .sql("DELETE FROM auth_tokens WHERE expires_at < ? OR revoked_at < ?")
                .params(threshold, threshold)
                .update();
    }
}

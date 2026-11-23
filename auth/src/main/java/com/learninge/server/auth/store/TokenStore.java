package com.learninge.server.auth.store;

import com.learninge.server.common.sqlite.SqliteDatabase;
import java.util.Optional;

/** SQL của {@code auth_tokens}. Chỉ lưu SHA-256 của token. */
public final class TokenStore {
  public record ActiveToken(String userUuid, long expiresAt) {}

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
            SELECT u.uuid, t.expires_at FROM auth_tokens t JOIN users u ON u.id = t.user_id
            WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > ?
            """)
        .params(hash, now)
        .query((rs, i) -> new ActiveToken(rs.getString("uuid"), rs.getLong("expires_at")))
        .optional();
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

  public int deleteEndedBefore(long threshold) {
    return database
        .writer()
        .sql("DELETE FROM auth_tokens WHERE expires_at < ? OR revoked_at < ?")
        .params(threshold, threshold)
        .update();
  }
}

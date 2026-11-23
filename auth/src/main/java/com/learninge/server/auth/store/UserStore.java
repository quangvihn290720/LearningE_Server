package com.learninge.server.auth.store;

import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.auth.domain.LockState;
import com.learninge.server.auth.domain.PasswordHasher.Hash;
import com.learninge.server.common.sqlite.SqliteDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL của {@code users} và {@code security_answers}. Không mở giao dịch, không chứa nghiệp vụ. */
public final class UserStore {
  private static final String SELECT =
      """
      SELECT u.id, u.uuid, u.email, u.nickname, u.display_name, u.password_hash, u.password_salt,
             u.failed_login_count, u.login_lock_count, u.login_locked_until, u.login_last_fail_at,
             u.failed_reset_count, u.reset_lock_count, u.reset_locked_until, u.reset_last_fail_at,
             u.created_at, u.updated_at, a.question_id, a.answer_hash, a.answer_salt
      FROM users u JOIN security_answers a ON a.user_id = u.id
      """;

  public record NewUser(
      String uuid,
      String email,
      String nickname,
      String displayName,
      Hash password,
      int questionId,
      Hash answer) {}

  public record PublicProfile(long id, String uuid, String nickname, long version) {}

  private final SqliteDatabase database;

  public UserStore(SqliteDatabase database) {
    this.database = database;
  }

  /** Đọc ngoài giao dịch. */
  public Optional<UserRow> findByEmail(String email) {
    return byEmail(database.reader(), email);
  }

  public Optional<UserRow> findByUuid(String uuid) {
    return byUuid(database.reader(), uuid);
  }

  /** Đọc trong giao dịch ghi: thấy đúng dữ liệu sắp sửa. */
  public Optional<UserRow> lockByEmail(String email) {
    return byEmail(database.writer(), email);
  }

  public Optional<UserRow> lockByUuid(String uuid) {
    return byUuid(database.writer(), uuid);
  }

  public long insert(NewUser user, long now) {
    long id =
        database
            .writer()
            .sql(
                """
                INSERT INTO users (uuid, email, nickname, display_name, password_hash,
                                   password_salt, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                user.uuid(),
                user.email(),
                user.nickname(),
                user.displayName(),
                user.password().hash(),
                user.password().salt(),
                now,
                now)
            .query(Long.class)
            .single();
    database
        .writer()
        .sql(
            """
            INSERT INTO security_answers (user_id, question_id, answer_hash, answer_salt, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """)
        .params(id, user.questionId(), user.answer().hash(), user.answer().salt(), now)
        .update();
    return id;
  }

  public void updateProfile(long id, String nickname, String displayName, long updatedAt) {
    database
        .writer()
        .sql("UPDATE users SET nickname = ?, display_name = ?, updated_at = ? WHERE id = ?")
        .params(nickname, displayName, updatedAt, id)
        .update();
  }

  public void updatePassword(long id, Hash password) {
    database
        .writer()
        .sql("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?")
        .params(password.hash(), password.salt(), id)
        .update();
  }

  public void saveLock(long id, LockScope scope, LockState state) {
    String sql =
        switch (scope) {
          case LOGIN ->
              """
              UPDATE users SET failed_login_count = ?, login_lock_count = ?,
                               login_locked_until = ?, login_last_fail_at = ? WHERE id = ?
              """;
          case RESET ->
              """
              UPDATE users SET failed_reset_count = ?, reset_lock_count = ?,
                               reset_locked_until = ?, reset_last_fail_at = ? WHERE id = ?
              """;
        };
    database
        .writer()
        .sql(sql)
        .params(state.failedCount(), state.lockCount(), state.lockedUntil(), state.lastFailAt(), id)
        .update();
  }

  /** Token và câu trả lời bảo mật xóa theo CASCADE. */
  public void delete(long id) {
    database.writer().sql("DELETE FROM users WHERE id = ?").param(id).update();
  }

  public List<PublicProfile> exportAfter(long afterId, int limit) {
    return database
        .reader()
        .sql("SELECT id, uuid, nickname, updated_at FROM users WHERE id > ? ORDER BY id LIMIT ?")
        .params(afterId, limit)
        .query(
            (rs, i) ->
                new PublicProfile(
                    rs.getLong("id"),
                    rs.getString("uuid"),
                    rs.getString("nickname"),
                    rs.getLong("updated_at")))
        .list();
  }

  private static Optional<UserRow> byEmail(JdbcClient client, String email) {
    return client.sql(SELECT + "WHERE u.email = ?").param(email).query(UserStore::map).optional();
  }

  private static Optional<UserRow> byUuid(JdbcClient client, String uuid) {
    return client.sql(SELECT + "WHERE u.uuid = ?").param(uuid).query(UserStore::map).optional();
  }

  private static UserRow map(ResultSet rs, int rowNumber) throws SQLException {
    return new UserRow(
        rs.getLong("id"),
        rs.getString("uuid"),
        rs.getString("email"),
        rs.getString("nickname"),
        rs.getString("display_name"),
        new Hash(rs.getString("password_hash"), rs.getString("password_salt")),
        rs.getInt("question_id"),
        new Hash(rs.getString("answer_hash"), rs.getString("answer_salt")),
        new LockState(
            rs.getInt("failed_login_count"),
            rs.getInt("login_lock_count"),
            rs.getLong("login_locked_until"),
            rs.getLong("login_last_fail_at")),
        new LockState(
            rs.getInt("failed_reset_count"),
            rs.getInt("reset_lock_count"),
            rs.getLong("reset_locked_until"),
            rs.getLong("reset_last_fail_at")),
        rs.getLong("created_at"),
        rs.getLong("updated_at"));
  }
}

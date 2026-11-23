package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Một bảng đồng bộ. Tên bảng và cột là hằng trong mã, không bao giờ lấy từ dữ liệu người dùng. */
public abstract class SyncTable {
  private final String table;

  SyncTable(String table) {
    this.table = table;
  }

  protected abstract RowMapper<Change> mapper();

  /** Các thay đổi sau mốc, kể cả tombstone, tăng theo {@code server_seq}. */
  final List<Change> changesAfter(JdbcClient client, String userId, long since, int limit) {
    return client
        .sql(
            "SELECT * FROM "
                + table
                + " WHERE user_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?")
        .params(userId, since, limit)
        .query(mapper())
        .list();
  }

  final void deleteUser(JdbcClient writer, String userId) {
    writer.sql("DELETE FROM " + table + " WHERE user_id = ?").param(userId).update();
  }

  /** Chủ của bản ghi có khóa {@code uuid}, hoặc null nếu chưa có. */
  public final String ownerOf(JdbcClient writer, String uuid) {
    return writer
        .sql("SELECT user_id FROM " + table + " WHERE uuid = ?")
        .param(uuid)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  public final long seqOf(JdbcClient writer, String uuid) {
    return writer
        .sql("SELECT server_seq FROM " + table + " WHERE uuid = ?")
        .param(uuid)
        .query(Long.class)
        .single();
  }

  public final void markDeleted(
      JdbcClient writer, String userId, String uuid, long updatedAt, long seq) {
    writer
        .sql(
            "UPDATE "
                + table
                + " SET deleted = 1, updated_at = ?, server_seq = ? WHERE uuid = ? AND user_id = ?")
        .params(updatedAt, seq, uuid, userId)
        .update();
  }
}

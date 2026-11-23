package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.FavoriteData;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bảng {@code favorites}. */
public final class FavoriteTable extends SyncTable {
  FavoriteTable() {
    super("favorites");
  }

  public void upsert(
      JdbcClient writer,
      String userId,
      String wordUid,
      FavoriteData data,
      boolean deleted,
      long updatedAt,
      long seq) {
    writer
        .sql(
            """
            INSERT INTO favorites (user_id, word_uid, created_at, updated_at, deleted, server_seq)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, word_uid) DO UPDATE SET updated_at = excluded.updated_at,
              deleted = excluded.deleted, server_seq = excluded.server_seq
            """)
        .params(userId, wordUid, data.getCreatedAtMs(), updatedAt, deleted ? 1 : 0, seq)
        .update();
  }

  @Override
  protected RowMapper<Change> mapper() {
    return (rs, i) ->
        Change.newBuilder()
            .setRecordId(rs.getString("word_uid"))
            .setUpdatedAtMs(rs.getLong("updated_at"))
            .setDeleted(rs.getBoolean("deleted"))
            .setServerSeq(rs.getLong("server_seq"))
            .setFavorite(FavoriteData.newBuilder().setCreatedAtMs(rs.getLong("created_at")))
            .build();
  }
}

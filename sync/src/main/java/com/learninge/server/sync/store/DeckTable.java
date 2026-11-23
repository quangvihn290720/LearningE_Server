package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.DeckData;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bảng {@code decks}. */
public final class DeckTable extends SyncTable {
  public record DeckRef(String userId, boolean deleted) {}

  DeckTable() {
    super("decks");
  }

  public Optional<DeckRef> find(JdbcClient writer, String uuid) {
    return writer
        .sql("SELECT user_id, deleted FROM decks WHERE uuid = ?")
        .param(uuid)
        .query((rs, i) -> new DeckRef(rs.getString("user_id"), rs.getBoolean("deleted")))
        .optional();
  }

  public void upsert(
      JdbcClient writer,
      String userId,
      String uuid,
      DeckData data,
      boolean isDefault,
      boolean deleted,
      long updatedAt,
      long seq) {
    writer
        .sql(
            """
            INSERT INTO decks (uuid, user_id, name, description, language_code, icon, is_default,
                               sort_order, created_at, updated_at, deleted, server_seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET name = excluded.name,
              description = excluded.description, language_code = excluded.language_code,
              icon = excluded.icon, sort_order = excluded.sort_order,
              updated_at = excluded.updated_at, deleted = excluded.deleted,
              server_seq = excluded.server_seq
            WHERE decks.user_id = excluded.user_id
            """)
        .params(
            uuid,
            userId,
            data.getName(),
            data.hasDescription() ? data.getDescription() : null,
            data.getLanguageCode(),
            data.hasIcon() ? data.getIcon() : null,
            isDefault ? 1 : 0,
            data.getSortOrder(),
            data.getCreatedAtMs(),
            updatedAt,
            deleted ? 1 : 0,
            seq)
        .update();
  }

  /** Tạo bộ mặc định; đã có thì không làm gì. Trả true nếu vừa tạo. */
  public boolean insertNotebook(
      JdbcClient writer, String userId, String uuid, String name, long now, long seq) {
    return writer
            .sql(
                """
                INSERT OR IGNORE INTO decks (uuid, user_id, name, language_code, is_default,
                                             created_at, updated_at, server_seq)
                VALUES (?, ?, ?, 'en', 1, ?, ?, ?)
                """)
            .params(uuid, userId, name, now, now, seq)
            .update()
        == 1;
  }

  @Override
  protected RowMapper<Change> mapper() {
    return (rs, i) -> {
      DeckData.Builder data =
          DeckData.newBuilder()
              .setName(rs.getString("name"))
              .setLanguageCode(rs.getString("language_code"))
              .setIsDefault(rs.getBoolean("is_default"))
              .setSortOrder(rs.getInt("sort_order"))
              .setCreatedAtMs(rs.getLong("created_at"));
      Columns.text(rs, "description", data::setDescription);
      Columns.text(rs, "icon", data::setIcon);
      return Change.newBuilder()
          .setRecordId(rs.getString("uuid"))
          .setUpdatedAtMs(rs.getLong("updated_at"))
          .setDeleted(rs.getBoolean("deleted"))
          .setServerSeq(rs.getLong("server_seq"))
          .setDeck(data)
          .build();
    };
  }
}

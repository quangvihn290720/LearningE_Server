package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.CefrLevel;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.SettingsData;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bảng {@code user_settings}: mỗi người dùng một dòng. */
public final class SettingsTable extends SyncTable {
  private static final String LEVEL_PREFIX = "CEFR_LEVEL_";

  SettingsTable() {
    super("user_settings");
  }

  public void upsert(
      JdbcClient writer, String userId, SettingsData data, long updatedAt, long seq) {
    writer
        .sql(
            """
            INSERT INTO user_settings (user_id, level, daily_goal, updated_at, server_seq)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET level = excluded.level,
              daily_goal = excluded.daily_goal, updated_at = excluded.updated_at,
              server_seq = excluded.server_seq
            """)
        .params(
            userId,
            data.getLevel().name().substring(LEVEL_PREFIX.length()),
            data.getDailyGoal(),
            updatedAt,
            seq)
        .update();
  }

  @Override
  protected RowMapper<Change> mapper() {
    return (rs, i) ->
        Change.newBuilder()
            .setUpdatedAtMs(rs.getLong("updated_at"))
            .setServerSeq(rs.getLong("server_seq"))
            .setSettings(
                SettingsData.newBuilder()
                    .setLevel(CefrLevel.valueOf(LEVEL_PREFIX + rs.getString("level")))
                    .setDailyGoal(rs.getInt("daily_goal")))
            .build();
  }
}

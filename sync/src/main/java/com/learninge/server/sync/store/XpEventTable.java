package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;
import com.learninge.proto.internal.v1.XpRecorded;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bảng {@code xp_events}: nguồn sự thật của XP, chỉ thêm. */
public final class XpEventTable extends SyncTable {
  private static final String PREFIX = "XP_SOURCE_TYPE_";

  /** Sự kiện đã được server tính XP. */
  public record ScoredXp(
      String uuid,
      String sourceType,
      int awarded,
      long occurredAt,
      long receivedAt,
      LocalDate localDate,
      LocalDate weekStart,
      long seq) {}

  XpEventTable() {
    super("xp_events");
  }

  public static String sourceName(XpSourceType type) {
    return type.name().substring(PREFIX.length());
  }

  public void insert(JdbcClient writer, String userId, XpEventData data, ScoredXp scored) {
    writer
        .sql(
            """
            INSERT INTO xp_events (uuid, user_id, source_type, ref_uuid, correct_count, wrong_count,
                                   total_count, xp_awarded, occurred_at, received_at, local_date,
                                   week_start, server_seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            scored.uuid(),
            userId,
            scored.sourceType(),
            data.hasRefUuid() ? data.getRefUuid() : null,
            data.getCorrectCount(),
            data.getWrongCount(),
            data.getCorrectCount() + data.getWrongCount(),
            scored.awarded(),
            scored.occurredAt(),
            scored.receivedAt(),
            scored.localDate().toString(),
            scored.weekStart().toString(),
            scored.seq())
        .update();
  }

  public int awardedOn(JdbcClient writer, String userId, LocalDate date) {
    return writer
        .sql(
            """
            SELECT COALESCE(SUM(xp_awarded), 0) FROM xp_events
            WHERE user_id = ? AND local_date = ?
            """)
        .params(userId, date.toString())
        .query(Integer.class)
        .single();
  }

  public Change find(JdbcClient writer, String uuid) {
    return writer
        .sql("SELECT * FROM xp_events WHERE uuid = ?")
        .param(uuid)
        .query(mapper())
        .single();
  }

  public List<XpRecorded> exportAfter(JdbcClient reader, long afterSeq, int limit) {
    return reader
        .sql("SELECT * FROM xp_events WHERE server_seq > ? ORDER BY server_seq LIMIT ?")
        .params(afterSeq, limit)
        .query(
            (rs, i) ->
                XpRecorded.newBuilder()
                    .setXpUuid(rs.getString("uuid"))
                    .setUserId(rs.getString("user_id"))
                    .setSourceType(rs.getString("source_type"))
                    .setXpAwarded(rs.getInt("xp_awarded"))
                    .setOccurredAtMs(rs.getLong("occurred_at"))
                    .setLocalDate(rs.getString("local_date"))
                    .setWeekStart(rs.getString("week_start"))
                    .setServerSeq(rs.getLong("server_seq"))
                    .setReceivedAtMs(rs.getLong("received_at"))
                    .build())
        .list();
  }

  @Override
  protected RowMapper<Change> mapper() {
    return (rs, i) -> {
      XpEventData.Builder data =
          XpEventData.newBuilder()
              .setSourceType(XpSourceType.valueOf(PREFIX + rs.getString("source_type")))
              .setCorrectCount(rs.getInt("correct_count"))
              .setWrongCount(rs.getInt("wrong_count"))
              .setTotalCount(rs.getInt("total_count"))
              .setOccurredAtMs(rs.getLong("occurred_at"))
              .setXpAwarded(rs.getInt("xp_awarded"));
      Columns.text(rs, "ref_uuid", data::setRefUuid);
      return Change.newBuilder()
          .setRecordId(rs.getString("uuid"))
          .setUpdatedAtMs(rs.getLong("received_at"))
          .setServerSeq(rs.getLong("server_seq"))
          .setXpEvent(data)
          .build();
    };
  }
}

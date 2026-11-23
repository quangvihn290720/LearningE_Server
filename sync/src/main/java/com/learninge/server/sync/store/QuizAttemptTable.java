package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.QuizAttemptData;
import com.learninge.proto.app.v1.QuizSourceType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bảng {@code quiz_attempts}: chỉ thêm. */
public final class QuizAttemptTable extends SyncTable {
  QuizAttemptTable() {
    super("quiz_attempts");
  }

  public void insert(
      JdbcClient writer,
      String userId,
      String uuid,
      QuizAttemptData data,
      long updatedAt,
      long seq) {
    writer
        .sql(
            """
            INSERT INTO quiz_attempts (uuid, user_id, source_type, deck_ref, score, correct_count,
                                       total_count, duration_sec, created_at, updated_at,
                                       server_seq)
            VALUES (?, ?, 'DECK', ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            uuid,
            userId,
            data.hasDeckRef() ? data.getDeckRef() : null,
            data.getScore(),
            data.getCorrectCount(),
            data.getTotalCount(),
            data.getDurationSec(),
            data.getCreatedAtMs(),
            updatedAt,
            seq)
        .update();
  }

  @Override
  protected RowMapper<Change> mapper() {
    return (rs, i) -> {
      QuizAttemptData.Builder data =
          QuizAttemptData.newBuilder()
              .setSourceType(QuizSourceType.QUIZ_SOURCE_TYPE_DECK)
              .setScore(rs.getInt("score"))
              .setCorrectCount(rs.getInt("correct_count"))
              .setTotalCount(rs.getInt("total_count"))
              .setDurationSec(rs.getInt("duration_sec"))
              .setCreatedAtMs(rs.getLong("created_at"));
      Columns.text(rs, "deck_ref", data::setDeckRef);
      return Change.newBuilder()
          .setRecordId(rs.getString("uuid"))
          .setUpdatedAtMs(rs.getLong("updated_at"))
          .setServerSeq(rs.getLong("server_seq"))
          .setQuizAttempt(data)
          .build();
    };
  }
}

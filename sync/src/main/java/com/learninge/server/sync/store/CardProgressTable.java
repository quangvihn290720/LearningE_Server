package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.CardProgressData;
import com.learninge.proto.app.v1.Change;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bảng {@code card_progress}: chỉ bản có {@code updated_at} lớn hơn mới được ghi đè (BR-062).
 */
public final class CardProgressTable extends SyncTable {
    CardProgressTable() {
        super("card_progress");
    }

    /**
     * Trả false nếu bản đang có mới hơn hoặc bằng (STALE).
     */
    public boolean upsertIfNewer(
            JdbcClient writer,
            String userId,
            String wordUid,
            CardProgressData data,
            long updatedAt,
            long seq) {
        return writer
                .sql(
                        """
                                INSERT INTO card_progress (user_id, word_uid, box, next_review_date,
                                    first_learned_date, last_reviewed_at, review_count, updated_at, server_seq)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (user_id, word_uid) DO UPDATE SET box = excluded.box,
                                  next_review_date = excluded.next_review_date,
                                  first_learned_date = excluded.first_learned_date,
                                  last_reviewed_at = excluded.last_reviewed_at,
                                  review_count = excluded.review_count, updated_at = excluded.updated_at,
                                  server_seq = excluded.server_seq
                                WHERE excluded.updated_at > card_progress.updated_at
                                """)
                .params(
                        userId,
                        wordUid,
                        data.getBox(),
                        data.getNextReviewDate(),
                        data.getFirstLearnedDate(),
                        data.hasLastReviewedAtMs() ? data.getLastReviewedAtMs() : null,
                        data.getReviewCount(),
                        updatedAt,
                        seq)
                .update()
                == 1;
    }

    public Change find(JdbcClient writer, String userId, String wordUid) {
        return writer
                .sql("SELECT * FROM card_progress WHERE user_id = ? AND word_uid = ?")
                .params(userId, wordUid)
                .query(mapper())
                .single();
    }

    /**
     * {@code updated_at} đang lưu, hoặc -1 nếu chưa có.
     */
    public long updatedAt(JdbcClient writer, String userId, String wordUid) {
        return writer
                .sql("SELECT updated_at FROM card_progress WHERE user_id = ? AND word_uid = ?")
                .params(userId, wordUid)
                .query(Long.class)
                .optional()
                .orElse(-1L);
    }

    @Override
    protected RowMapper<Change> mapper() {
        return (rs, i) -> {
            CardProgressData.Builder data =
                    CardProgressData.newBuilder()
                            .setBox(rs.getInt("box"))
                            .setNextReviewDate(rs.getString("next_review_date"))
                            .setFirstLearnedDate(rs.getString("first_learned_date"))
                            .setReviewCount(rs.getInt("review_count"));
            Columns.number(rs, "last_reviewed_at", data::setLastReviewedAtMs);
            return Change.newBuilder()
                    .setRecordId(rs.getString("word_uid"))
                    .setUpdatedAtMs(rs.getLong("updated_at"))
                    .setServerSeq(rs.getLong("server_seq"))
                    .setCardProgress(data)
                    .build();
        };
    }
}

package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.ExamAttemptData;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bảng {@code exam_attempts}: chỉ thêm, chỉ lượt đã nộp.
 */
public final class ExamAttemptTable extends SyncTable {
    ExamAttemptTable() {
        super("exam_attempts");
    }

    public void insert(
            JdbcClient writer,
            String userId,
            String uuid,
            ExamAttemptData data,
            long updatedAt,
            long seq) {
        writer
                .sql(
                        """
                                INSERT INTO exam_attempts (uuid, user_id, test_code, score, correct_count, total_count,
                                                           duration_sec, started_at, submitted_at, updated_at,
                                                           server_seq)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                .params(
                        uuid,
                        userId,
                        data.getTestCode(),
                        data.getScore(),
                        data.getCorrectCount(),
                        data.getTotalCount(),
                        data.getDurationSec(),
                        data.getStartedAtMs(),
                        data.getSubmittedAtMs(),
                        updatedAt,
                        seq)
                .update();
    }

    @Override
    protected RowMapper<Change> mapper() {
        return (rs, i) ->
                Change.newBuilder()
                        .setRecordId(rs.getString("uuid"))
                        .setUpdatedAtMs(rs.getLong("updated_at"))
                        .setServerSeq(rs.getLong("server_seq"))
                        .setExamAttempt(
                                ExamAttemptData.newBuilder()
                                        .setTestCode(rs.getString("test_code"))
                                        .setScore(rs.getInt("score"))
                                        .setCorrectCount(rs.getInt("correct_count"))
                                        .setTotalCount(rs.getInt("total_count"))
                                        .setDurationSec(rs.getInt("duration_sec"))
                                        .setStartedAtMs(rs.getLong("started_at"))
                                        .setSubmittedAtMs(rs.getLong("submitted_at")))
                        .build();
    }
}

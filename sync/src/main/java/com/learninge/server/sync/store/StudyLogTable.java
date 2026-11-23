package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.ActivityType;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.StudyLogData;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bảng {@code study_log}: chỉ thêm.
 */
public final class StudyLogTable extends SyncTable {
    private static final String PREFIX = "ACTIVITY_TYPE_";

    StudyLogTable() {
        super("study_log");
    }

    public void insert(
            JdbcClient writer, String userId, String uuid, StudyLogData data, long updatedAt, long seq) {
        writer
                .sql(
                        """
                                INSERT INTO study_log (uuid, user_id, activity_type, ref_uuid, study_date, created_at,
                                                       updated_at, server_seq)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                .params(
                        uuid,
                        userId,
                        data.getActivityType().name().substring(PREFIX.length()),
                        data.hasRefUuid() ? data.getRefUuid() : null,
                        data.getStudyDate(),
                        data.getCreatedAtMs(),
                        updatedAt,
                        seq)
                .update();
    }

    /**
     * Các ngày học khác nhau, tăng dần; đọc trong giao dịch ghi để thấy dòng vừa thêm.
     */
    public List<LocalDate> studyDays(JdbcClient writer, String userId) {
        return writer
                .sql("SELECT DISTINCT study_date FROM study_log WHERE user_id = ? ORDER BY study_date")
                .param(userId)
                .query((rs, i) -> LocalDate.parse(rs.getString(1)))
                .list();
    }

    @Override
    protected RowMapper<Change> mapper() {
        return (rs, i) -> {
            StudyLogData.Builder data =
                    StudyLogData.newBuilder()
                            .setActivityType(ActivityType.valueOf(PREFIX + rs.getString("activity_type")))
                            .setStudyDate(rs.getString("study_date"))
                            .setCreatedAtMs(rs.getLong("created_at"));
            Columns.text(rs, "ref_uuid", data::setRefUuid);
            return Change.newBuilder()
                    .setRecordId(rs.getString("uuid"))
                    .setUpdatedAtMs(rs.getLong("updated_at"))
                    .setServerSeq(rs.getLong("server_seq"))
                    .setStudyLog(data)
                    .build();
        };
    }
}

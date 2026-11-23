package com.learninge.server.sync.store;

import com.learninge.proto.internal.v1.ProgressChanged;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.sync.domain.Streak;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bảng {@code user_stats}: tổng XP và chuỗi ngày do server tính.
 */
public final class UserStatsStore {
    public record UserStats(long totalXp, Streak streak, long version) {
        public static final UserStats NONE = new UserStats(0, Streak.NONE, 0);
    }

    private final SqliteDatabase database;

    public UserStatsStore(SqliteDatabase database) {
        this.database = database;
    }

    public Optional<UserStats> find(String userId) {
        return find(database.reader(), userId);
    }

    public UserStats lock(String userId) {
        return find(database.writer(), userId).orElse(UserStats.NONE);
    }

    public void save(String userId, UserStats stats) {
        LocalDate last = stats.streak().lastStudyDate();
        database
                .writer()
                .sql(
                        """
                                INSERT INTO user_stats (user_id, total_xp, current_streak, longest_streak,
                                                        last_study_date, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT (user_id) DO UPDATE SET total_xp = excluded.total_xp,
                                  current_streak = excluded.current_streak, longest_streak = excluded.longest_streak,
                                  last_study_date = excluded.last_study_date, updated_at = excluded.updated_at
                                """)
                .params(
                        userId,
                        stats.totalXp(),
                        stats.streak().current(),
                        stats.streak().longest(),
                        last == null ? null : last.toString(),
                        stats.version())
                .update();
    }

    public void delete(String userId) {
        database.writer().sql("DELETE FROM user_stats WHERE user_id = ?").param(userId).update();
    }

    public List<ProgressChanged> exportAfter(String afterUserId, int limit) {
        return database
                .reader()
                .sql("SELECT * FROM user_stats WHERE user_id > ? ORDER BY user_id LIMIT ?")
                .params(afterUserId, limit)
                .query(
                        (rs, i) -> {
                            UserStats stats = map(rs, i);
                            LocalDate last = stats.streak().lastStudyDate();
                            return ProgressChanged.newBuilder()
                                    .setUserId(rs.getString("user_id"))
                                    .setCurrentStreak(stats.streak().current())
                                    .setLongestStreak(stats.streak().longest())
                                    .setLastStudyDate(last == null ? "" : last.toString())
                                    .setVersion(stats.version())
                                    .build();
                        })
                .list();
    }

    private static Optional<UserStats> find(JdbcClient client, String userId) {
        return client
                .sql("SELECT * FROM user_stats WHERE user_id = ?")
                .param(userId)
                .query(UserStatsStore::map)
                .optional();
    }

    private static UserStats map(ResultSet rs, int rowNumber) throws SQLException {
        String last = rs.getString("last_study_date");
        return new UserStats(
                rs.getLong("total_xp"),
                new Streak(
                        rs.getInt("current_streak"),
                        rs.getInt("longest_streak"),
                        last == null ? null : LocalDate.parse(last)),
                rs.getLong("updated_at"));
    }
}

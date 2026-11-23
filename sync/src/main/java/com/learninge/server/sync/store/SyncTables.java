package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.server.common.sqlite.SqliteDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Chín bảng đồng bộ, bộ đếm {@code server_seq} và danh sách người dùng đã xóa.
 */
public final class SyncTables {
    public final SettingsTable settings = new SettingsTable();
    public final DeckTable decks = new DeckTable();
    public final WordTable words = new WordTable();
    public final CardProgressTable cardProgress = new CardProgressTable();
    public final FavoriteTable favorites = new FavoriteTable();
    public final StudyLogTable studyLog = new StudyLogTable();
    public final QuizAttemptTable quizAttempts = new QuizAttemptTable();
    public final ExamAttemptTable examAttempts = new ExamAttemptTable();
    public final XpEventTable xpEvents = new XpEventTable();

    private final List<SyncTable> all =
            List.of(
                    settings,
                    decks,
                    words,
                    cardProgress,
                    favorites,
                    studyLog,
                    quizAttempts,
                    examAttempts,
                    xpEvents);
    private final SqliteDatabase database;

    public SyncTables(SqliteDatabase database) {
        this.database = database;
    }

    public JdbcClient writer() {
        return database.writer();
    }

    public JdbcClient reader() {
        return database.reader();
    }

    /**
     * Số thứ tự thay đổi kế tiếp; chỉ gọi trong giao dịch ghi.
     */
    public long nextSeq() {
        return writer()
                .sql("UPDATE sync_seq SET value = value + 1 WHERE id = 1 RETURNING value")
                .query(Long.class)
                .single();
    }

    /**
     * Tối đa {@code limit} thay đổi sau mốc, gộp từ 9 bảng và sắp theo {@code server_seq}.
     */
    public List<Change> changesAfter(String userId, long since, int limit) {
        List<Change> merged = new ArrayList<>();
        for (SyncTable table : all) {
            merged.addAll(table.changesAfter(reader(), userId, since, limit));
        }
        merged.sort(Comparator.comparingLong(Change::getServerSeq));
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    public boolean isPurged(String userId) {
        return writer()
                .sql("SELECT 1 FROM purged_users WHERE user_id = ?")
                .param(userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void purge(String userId, long now) {
        for (SyncTable table : all) {
            table.deleteUser(writer(), userId);
        }
        writer()
                .sql("INSERT OR IGNORE INTO purged_users (user_id, purged_at) VALUES (?, ?)")
                .params(userId, now)
                .update();
    }
}

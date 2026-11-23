package com.learninge.server.sync.service;

import com.learninge.server.common.id.UuidV5;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.sync.store.SyncTables;
import com.learninge.server.sync.store.UserStatsStore;
import java.time.Clock;

/** Phản ứng với sự kiện tài khoản từ auth. Mọi thao tác chạy lại không đổi kết quả. */
public final class UserLifecycle {
  private static final String NOTEBOOK_NAME = "Sổ tay của tôi";

  private final SqliteDatabase database;
  private final SyncTables tables;
  private final UserStatsStore stats;
  private final Clock clock;

  public UserLifecycle(
      SqliteDatabase database, SyncTables tables, UserStatsStore stats, Clock clock) {
    this.database = database;
    this.tables = tables;
    this.stats = stats;
    this.clock = clock;
  }

  /** BR-043: tạo bộ mặc định với uuid tất định. Sự kiện đến sau khi xóa tài khoản thì bỏ qua. */
  public void onRegistered(String userId) {
    database.inWriteTx(
        () -> {
          String uuid = UuidV5.notebook(userId);
          if (!tables.isPurged(userId) && tables.decks.find(tables.writer(), uuid).isEmpty()) {
            tables.decks.insertNotebook(
                tables.writer(), userId, uuid, NOTEBOOK_NAME, clock.millis(), tables.nextSeq());
          }
        });
  }

  /** Xóa mọi dòng và ghi dấu để lô Push đến muộn không làm dữ liệu "sống lại". */
  public void onDeleted(String userId) {
    database.inWriteTx(
        () -> {
          tables.purge(userId, clock.millis());
          stats.delete(userId);
        });
  }
}

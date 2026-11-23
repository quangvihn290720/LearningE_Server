package com.learninge.server.common.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Một file SQLite: một kết nối ghi duy nhất (giao dịch {@code BEGIN IMMEDIATE}) và bốn kết nối đọc.
 *
 * <p>WAL cho phép đọc song song với ghi; một kết nối ghi tuần tự hóa mọi thay đổi nên không có
 * {@code SQLITE_BUSY} giữa các luồng của chính dịch vụ.
 */
public final class SqliteDatabase implements AutoCloseable {
  private static final int BUSY_TIMEOUT_MS = 5000;
  private static final int READERS = 4;

  private final HikariDataSource writerSource;
  private final HikariDataSource readerSource;
  private final JdbcClient writer;
  private final JdbcClient reader;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  private SqliteDatabase(Path file) {
    writerSource = pool("writer", source(file, SQLiteConfig.TransactionMode.IMMEDIATE), 1, null);
    readerSource =
        pool(
            "reader",
            source(file, SQLiteConfig.TransactionMode.DEFERRED),
            READERS,
            "PRAGMA query_only = ON");
    writer = JdbcClient.create(writerSource);
    reader = JdbcClient.create(readerSource);
    writeTx = new TransactionTemplate(new DataSourceTransactionManager(writerSource));
    readTx = new TransactionTemplate(new DataSourceTransactionManager(readerSource));
  }

  /** Kiểm file, chạy Flyway (có sao lưu trước khi đổi lược đồ) rồi mở các pool kết nối. */
  public static SqliteDatabase open(Path file, String migrationLocation) {
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    boolean existed = Files.exists(file);
    DataSource direct = source(file, SQLiteConfig.TransactionMode.IMMEDIATE);
    if (existed) {
      quickCheck(direct, file);
    }
    migrate(direct, file, migrationLocation, existed);
    return new SqliteDatabase(file);
  }

  /** Câu lệnh ghi (hoặc đọc bên trong giao dịch ghi). */
  public JdbcClient writer() {
    return writer;
  }

  /** Câu lệnh chỉ đọc, chạy song song với ghi. */
  public JdbcClient reader() {
    return reader;
  }

  public <T> T inWriteTx(Supplier<T> work) {
    return writeTx.execute(status -> work.get());
  }

  public void inWriteTx(Runnable work) {
    writeTx.executeWithoutResult(status -> work.run());
  }

  /** Nhiều câu đọc nhìn cùng một ảnh chụp dữ liệu. */
  public <T> T inReadTx(Supplier<T> work) {
    return readTx.execute(status -> work.get());
  }

  public void backupTo(Path target) {
    vacuumInto(writer, target);
  }

  @Override
  public void close() {
    readerSource.close();
    writerSource.close();
  }

  private static SQLiteDataSource source(Path file, SQLiteConfig.TransactionMode mode) {
    SQLiteConfig config = new SQLiteConfig();
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    config.setBusyTimeout(BUSY_TIMEOUT_MS);
    config.setTransactionMode(mode);
    // sqlite-jdbc tắt khóa ngoại mặc định; phải bật trên từng kết nối (TM-27).
    config.enforceForeignKeys(true);
    SQLiteDataSource source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
    return source;
  }

  private static HikariDataSource pool(
      String name, DataSource source, int size, String connectionInitSql) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("sqlite-" + name);
    config.setDataSource(source);
    config.setMaximumPoolSize(size);
    config.setConnectionTimeout(BUSY_TIMEOUT_MS);
    config.setConnectionInitSql(connectionInitSql);
    return new HikariDataSource(config);
  }

  private static void quickCheck(DataSource source, Path file) {
    String result =
        JdbcClient.create(source).sql("PRAGMA quick_check").query(String.class).single();
    if (!"ok".equals(result)) {
      throw new IllegalStateException("File SQLite hỏng: " + file.getFileName());
    }
  }

  private static void migrate(DataSource source, Path file, String location, boolean existed) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:" + location)
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .load();
    MigrationInfo[] pending = flyway.info().pending();
    if (existed && pending.length > 0) {
      Path backup = Path.of(file + ".bak-" + pending[0].getVersion());
      vacuumInto(JdbcClient.create(source), backup);
    }
    flyway.migrate();
  }

  private static void vacuumInto(JdbcClient client, Path target) {
    try {
      Files.createDirectories(target.toAbsolutePath().getParent());
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    client.sql("VACUUM INTO ?").param(target.toAbsolutePath().toString()).update();
  }
}

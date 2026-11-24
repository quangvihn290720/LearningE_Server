package com.learninge.server.common.sqlite;

import com.learninge.server.common.ops.OpsCommand;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * {@code --ops=restore --from=<tệp>}: thay file SQLite bằng một bản sao lưu. Việc thay tệp phải xong
 * <b>trước</b> khi kho được mở, nên bean {@code SqliteDatabase} gọi {@link #replaceIfRequested} đầu
 * tiên; sau khi mở, lệnh chạy phần riêng của dịch vụ ({@code afterRestore}).
 */
public final class SqliteRestore implements OpsCommand {
    private static final Logger log = LoggerFactory.getLogger(SqliteRestore.class);
    private static final String NAME = "restore";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final SqliteDatabase database;
    private final Runnable afterRestore;

    /**
     * @param afterRestore chạy trong giao dịch ghi, ngay sau khi kho đã được thay
     */
    public SqliteRestore(SqliteDatabase database, Runnable afterRestore) {
        this.database = database;
        this.afterRestore = afterRestore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        database.inWriteTx(afterRestore);
        log.info("Đã khôi phục từ {}", OpsCommand.required(arguments, "from"));
    }

    public static void replaceIfRequested(ApplicationArguments arguments, Path file, Clock clock) {
        List<String> ops = arguments.getOptionValues("ops");
        if (ops != null && ops.contains(NAME)) {
            replace(Path.of(OpsCommand.required(arguments, "from")), file, clock);
        }
    }

    /**
     * Bản đang dùng được giữ lại với đuôi {@code .before-restore-…} để còn quay lui.
     */
    static void replace(Path from, Path file, Clock clock) {
        SqliteDatabase.verify(from);
        try {
            if (Files.exists(file)) {
                requireStopped(file);
                Files.move(
                        file, Path.of(file + ".before-restore-" + STAMP.format(clock.instant())));
            }
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path partial = Path.of(file + ".part");
            Files.copy(from, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rời chế độ WAL cần khóa độc quyền: còn tiến trình nào mở file thì SQLite báo bận. Việc này cũng
     * gộp WAL vào file chính nên bản giữ lại là bản đủ. File hỏng thì không cần hỏi.
     */
    private static void requireStopped(Path file) {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(500);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            JdbcClient.create(source).sql("PRAGMA journal_mode = DELETE").query(String.class).single();
        } catch (DataAccessException e) {
            if (SqliteErrors.isBusy(e)) {
                throw new IllegalStateException("Dịch vụ còn đang mở kho; dừng nó trước khi khôi phục");
            }
            log.warn("Kho hiện tại không đọc được, vẫn giữ lại để kiểm tra sau");
        }
    }
}

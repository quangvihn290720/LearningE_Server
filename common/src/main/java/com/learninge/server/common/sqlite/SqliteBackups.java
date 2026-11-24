package com.learninge.server.common.sqlite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Các bản sao lưu {@code <tên>-<yyyyMMdd-HHmmss>.db} (giờ UTC) trong một thư mục. Thời điểm nằm trong
 * tên tệp nên chép đi nơi khác vẫn sắp đúng thứ tự.
 */
public final class SqliteBackups {
    private static final Logger log = LoggerFactory.getLogger(SqliteBackups.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SqliteDatabase database;
    private final String prefix;
    private final Clock clock;
    private final Pattern fileName;

    public SqliteBackups(SqliteDatabase database, String prefix, Clock clock) {
        this.database = database;
        this.prefix = prefix;
        this.clock = clock;
        this.fileName = Pattern.compile(Pattern.quote(prefix) + "-\\d{8}-\\d{6}\\.db");
    }

    public Path backup(Path directory) {
        String stamp = STAMP.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        Path target = directory.resolve(prefix + "-" + stamp + ".db");
        database.backupTo(target);
        log.info("Đã sao lưu {}", target.getFileName());
        return target;
    }

    /**
     * Sao lưu nếu bản mới nhất đã cũ hơn {@code interval}, rồi chỉ giữ {@code keep} bản mới nhất.
     */
    public void backupIfDue(Path directory, Duration interval, int keep) {
        List<Path> existing = list(directory);
        Instant newest = existing.isEmpty() ? Instant.MIN : takenAt(existing.getLast());
        if (newest.isAfter(clock.instant().minus(interval))) {
            return;
        }
        backup(directory);
        List<Path> all = list(directory);
        for (Path old : all.subList(0, Math.max(0, all.size() - Math.max(1, keep)))) {
            delete(old);
        }
    }

    /**
     * Cũ trước, mới sau.
     */
    List<Path> list(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> fileName.matcher(f.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Instant takenAt(Path file) {
        String name = file.getFileName().toString();
        String stamp = name.substring(prefix.length() + 1, name.length() - ".db".length());
        return LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC);
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.learninge.server.common.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learninge.server.common.testing.MutableClock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBackupsTest {
    private static final Duration DAY = Duration.ofDays(1);

    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at("2026-11-24T00:00:00Z");
    private Path file;
    private Path target;
    private SqliteDatabase db;
    private SqliteBackups backups;

    @BeforeEach
    void setUp() {
        file = dir.resolve("a.db");
        target = dir.resolve("backup");
        db = open();
        backups = new SqliteBackups(db, "auth", clock);
    }

    private SqliteDatabase open() {
        return SqliteDatabase.open(file, "db/migration/outboxtest");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private List<String> names() {
        return backups.list(target).stream().map(p -> p.getFileName().toString()).toList();
    }

    @Test
    void runsOnceADayAndKeepsOnlyTheNewest() throws Exception {
        for (int day = 0; day < 5; day++) {
            backups.backupIfDue(target, DAY, 3);
            backups.backupIfDue(target, DAY, 3);
            clock.advance(DAY);
        }
        Files.writeString(target.resolve("sync-20200101-000000.db"), "của dịch vụ khác");
        backups.backupIfDue(target, DAY, 3);
        assertThat(names())
                .containsExactly(
                        "auth-20261127-000000.db", "auth-20261128-000000.db", "auth-20261129-000000.db");
        assertThat(target.resolve("sync-20200101-000000.db")).exists();
        try (var left = Files.list(target)) {
            assertThat(left.map(Path::toString)).noneMatch(n -> n.endsWith(".part"));
        }
    }

    @Test
    void backsUpWhileAWriteTransactionIsOpen() {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch copied = new CountDownLatch(1);
        CompletableFuture<Void> writing =
                CompletableFuture.runAsync(
                        () ->
                                db.inWriteTx(
                                        () -> {
                                            db.writer().sql("INSERT INTO parent VALUES (1)").update();
                                            written.countDown();
                                            await(copied);
                                        }));
        await(written);
        SqliteDatabase.verify(backups.backup(target));
        copied.countDown();
        writing.join();
        assertThat(count()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void restoreReplacesTheFileAndKeepsTheOldOne() {
        db.writer().sql("INSERT INTO parent VALUES (1)").update();
        Path backup = backups.backup(target);
        db.writer().sql("INSERT INTO parent VALUES (2)").update();
        db.close();

        SqliteRestore.replace(backup, file, clock);
        db = open();
        assertThat(count()).isEqualTo(1);
        Path old = Path.of(file + ".before-restore-20261124-000000");
        SqliteDatabase.verify(old);
        try (SqliteDatabase previous = SqliteDatabase.open(old, "db/migration/outboxtest")) {
            assertThat(previous.reader().sql("SELECT COUNT(*) FROM parent").query(Long.class).single())
                    .isEqualTo(2);
        }
    }

    @Test
    void refusesToRestoreWhileTheServiceIsRunningOrFromABadFile() throws Exception {
        db.writer().sql("INSERT INTO parent VALUES (1)").update();
        Path backup = backups.backup(target);
        assertThatThrownBy(() -> SqliteRestore.replace(backup, file, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dừng");
        Path bad = dir.resolve("bad.db");
        Files.writeString(bad, "not a database ".repeat(500));
        db.close();
        assertThatThrownBy(() -> SqliteRestore.replace(bad, file, clock)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SqliteRestore.replace(dir.resolve("none.db"), file, clock))
                .hasMessageContaining("Không có tệp");
        db = open();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void restoresOverACorruptFile() throws Exception {
        Path backup = backups.backup(target);
        db.close();
        Files.writeString(file, "hỏng ".repeat(1000));
        SqliteRestore.replace(backup, file, clock);
        db = open();
        assertThat(count()).isZero();
    }

    private long count() {
        return db.reader().sql("SELECT COUNT(*) FROM parent").query(Long.class).single();
    }
}

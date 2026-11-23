package com.learninge.server.common.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

class SqliteDatabaseTest {
    @TempDir
    Path dir;

    private SqliteDatabase open() {
        return SqliteDatabase.open(dir.resolve("a.db"), "db/migration/outboxtest");
    }

    @Test
    void enforcesForeignKeysOnEveryConnection() {
        try (SqliteDatabase db = open()) {
            assertThatThrownBy(() -> db.writer().sql("INSERT INTO child VALUES (1)").update())
                    .hasStackTraceContaining("FOREIGN KEY");
        }
    }

    @Test
    void readersCannotWrite() {
        try (SqliteDatabase db = open()) {
            assertThatThrownBy(() -> db.reader().sql("INSERT INTO parent VALUES (1)").update())
                    .hasStackTraceContaining("readonly");
        }
    }

    @Test
    void rollsBackOnException() {
        try (SqliteDatabase db = open()) {
            Runnable failing =
                    () -> {
                        db.writer().sql("INSERT INTO parent VALUES (1)").update();
                        throw new IllegalStateException("boom");
                    };
            assertThatThrownBy(() -> db.inWriteTx(failing)).hasMessage("boom");
            assertThat(count(db)).isZero();
        }
    }

    @Test
    void concurrentWritersNeverLoseUpdates() {
        try (SqliteDatabase db = open()) {
            db.writer().sql("INSERT INTO parent VALUES (1)").update();
            CompletableFuture<?>[] all =
                    IntStream.range(0, 200)
                            .mapToObj(
                                    i ->
                                            CompletableFuture.runAsync(
                                                    () ->
                                                            db.inWriteTx(
                                                                    () -> {
                                                                        long max =
                                                                                db.writer()
                                                                                        .sql("SELECT MAX(id) FROM parent")
                                                                                        .query(Long.class)
                                                                                        .single();
                                                                        db.writer()
                                                                                .sql("INSERT INTO parent VALUES (?)")
                                                                                .param(max + 1)
                                                                                .update();
                                                                    })))
                            .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(all).join();
            assertThat(count(db)).isEqualTo(201);
        }
    }

    @Test
    void backsUpBeforeUpgradingSchemaAndKeepsData() {
        Path file = dir.resolve("up.db");
        try (SqliteDatabase db = SqliteDatabase.open(file, "db/migration/outboxtest2a")) {
            db.writer().sql("INSERT INTO parent VALUES (7)").update();
        }
        try (SqliteDatabase db = SqliteDatabase.open(file, "db/migration/outboxtest2")) {
            assertThat(count(db)).isEqualTo(1);
        }
        assertThat(dir.resolve("up.db.bak-002")).exists();
    }

    @Test
    void refusesToOpenCorruptFile() throws Exception {
        Path file = dir.resolve("bad.db");
        Files.writeString(file, "this is not a database, it is plain text ".repeat(200));
        assertThatThrownBy(() -> SqliteDatabase.open(file, "db/migration/outboxtest"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void healthFollowsTheDatabase() {
        SqliteDatabase db = open();
        SqliteHealthIndicator health = new SqliteHealthIndicator(db);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        db.close();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void backupIsAReadableDatabase() {
        try (SqliteDatabase db = open()) {
            db.writer().sql("INSERT INTO parent VALUES (1)").update();
            db.backupTo(dir.resolve("copy.db"));
        }
        try (SqliteDatabase copy =
                     SqliteDatabase.open(dir.resolve("copy.db"), "db/migration/outboxtest")) {
            assertThat(count(copy)).isEqualTo(1);
        }
    }

    private static long count(SqliteDatabase db) {
        return db.reader().sql("SELECT COUNT(*) FROM parent").query(Long.class).single();
    }
}

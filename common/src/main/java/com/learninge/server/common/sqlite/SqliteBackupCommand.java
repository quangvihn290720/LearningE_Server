package com.learninge.server.common.sqlite;

import com.learninge.server.common.ops.OpsCommand;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=backup --out=<thư mục>}: chép nhất quán cả khi dịch vụ đang chạy.
 */
public final class SqliteBackupCommand implements OpsCommand {
    private final SqliteBackups backups;

    public SqliteBackupCommand(SqliteBackups backups) {
        this.backups = backups;
    }

    @Override
    public String name() {
        return "backup";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        backups.backup(Path.of(OpsCommand.required(arguments, "out")));
    }
}

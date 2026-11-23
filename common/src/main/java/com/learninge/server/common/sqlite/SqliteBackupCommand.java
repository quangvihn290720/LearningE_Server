package com.learninge.server.common.sqlite;

import com.learninge.server.common.ops.OpsCommand;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.ApplicationArguments;

/** {@code --ops=backup --out=<thư mục>}: chép nhất quán cả khi dịch vụ đang chạy. */
public final class SqliteBackupCommand implements OpsCommand {
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final SqliteDatabase database;
  private final String prefix;
  private final Clock clock;

  public SqliteBackupCommand(SqliteDatabase database, String prefix, Clock clock) {
    this.database = database;
    this.prefix = prefix;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "backup";
  }

  @Override
  public void run(ApplicationArguments arguments) {
    Path directory = Path.of(OpsCommand.required(arguments, "out"));
    database.backupTo(directory.resolve(prefix + "-" + STAMP.format(clock.instant()) + ".db"));
  }
}

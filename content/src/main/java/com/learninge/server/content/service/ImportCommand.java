package com.learninge.server.content.service;

import com.learninge.server.common.ops.OpsCommand;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/** {@code --ops=import --dir=/content-data}. */
public final class ImportCommand implements OpsCommand {
  private static final Logger log = LoggerFactory.getLogger(ImportCommand.class);

  private final ContentImporter importer;

  public ImportCommand(ContentImporter importer) {
    this.importer = importer;
  }

  @Override
  public String name() {
    return "import";
  }

  @Override
  public void run(ApplicationArguments arguments) {
    var result = importer.importFrom(Path.of(OpsCommand.required(arguments, "dir")));
    log.info("content_version={} đã đổi: {}", result.contentVersion(), result.changed());
  }
}

package com.learninge.server.common.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Dịch vụ vẫn lên khi Mongo ngừng: thử tạo lược đồ ở nền cho tới khi được, sau đó ping định kỳ. Health
 * đọc kết quả đã lưu nên không bao giờ chờ Mongo.
 */
public final class MongoReadiness implements HealthIndicator {
  static final Duration PERIOD = Duration.ofSeconds(2);

  private static final Logger log = LoggerFactory.getLogger(MongoReadiness.class);

  private final MongoDatabase database;
  private final MongoSchema schema;
  private boolean schemaApplied;
  private volatile boolean ready;

  MongoReadiness(MongoDatabase database, MongoSchema schema) {
    this.database = database;
    this.schema = schema;
  }

  synchronized void refresh() {
    try {
      if (!schemaApplied) {
        schema.apply(database);
        schemaApplied = true;
      }
      database.runCommand(new Document("ping", 1));
      if (!ready) {
        log.info("MongoDB sẵn sàng");
      }
      ready = true;
    } catch (MongoException e) {
      if (ready || !schemaApplied) {
        log.warn("MongoDB chưa sẵn sàng: {}", e.getClass().getSimpleName());
      }
      ready = false;
    }
  }

  @Override
  public Health health() {
    return ready ? Health.up().build() : Health.down().build();
  }
}

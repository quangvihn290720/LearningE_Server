package com.learninge.server.sync;

import com.learninge.server.common.config.InternalServerConfiguration;
import com.learninge.server.common.config.LearningeProperties;
import com.learninge.server.common.config.SecretKeys;
import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.outbox.OutboxConfiguration;
import com.learninge.server.common.outbox.OutboxConfiguration.EventSource;
import com.learninge.server.common.outbox.OutboxWriter;
import com.learninge.server.common.security.UserPseudonym;
import com.learninge.server.common.sqlite.SqliteBackupCommand;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.sqlite.SqliteErrors;
import com.learninge.server.common.sqlite.SqliteHealthIndicator;
import com.learninge.server.sync.grpc.SyncBackendService;
import com.learninge.server.sync.grpc.SyncEventSink;
import com.learninge.server.sync.service.PullService;
import com.learninge.server.sync.service.PushService;
import com.learninge.server.sync.service.RecordWriter;
import com.learninge.server.sync.service.SyncEvents;
import com.learninge.server.sync.service.UserLifecycle;
import com.learninge.server.sync.store.SyncTables;
import com.learninge.server.sync.store.UserStatsStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Dây nối của dịch vụ sync: kho → nghiệp vụ → gRPC. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
@Import({InternalServerConfiguration.class, OutboxConfiguration.class})
public class SyncServiceConfig {

  @Bean
  SqliteDatabase database(SyncProperties properties) {
    return SqliteDatabase.open(properties.dbPath(), "db/migration/sync");
  }

  @Bean
  SqliteHealthIndicator sqliteHealthIndicator(SqliteDatabase database) {
    return new SqliteHealthIndicator(database);
  }

  @Bean
  ErrorMappingInterceptor.Translator sqliteErrorTranslator() {
    return SqliteErrors::translate;
  }

  @Bean
  EventSource eventSource() {
    return new EventSource(SyncApplication.NAME);
  }

  @Bean
  SqliteBackupCommand backupCommand(SqliteDatabase database, Clock clock) {
    return new SqliteBackupCommand(database, SyncApplication.NAME, clock);
  }

  @Bean
  SyncTables syncTables(SqliteDatabase database) {
    return new SyncTables(database);
  }

  @Bean
  UserStatsStore userStatsStore(SqliteDatabase database) {
    return new UserStatsStore(database);
  }

  @Bean
  UserPseudonym userPseudonym(SyncProperties properties, LearningeProperties common) {
    return new UserPseudonym(
        SecretKeys.hmacKey("LEARNINGE_PSEUDONYM_KEY", properties.pseudonymKey(), common.env()));
  }

  @Bean
  SyncEvents syncEvents(OutboxWriter outbox, UserPseudonym pseudonym) {
    return new SyncEvents(outbox, pseudonym);
  }

  @Bean
  RecordWriter recordWriter(SyncTables tables) {
    return new RecordWriter(tables);
  }

  @Bean
  PushService pushService(
      SqliteDatabase database,
      SyncTables tables,
      UserStatsStore stats,
      RecordWriter records,
      SyncEvents events,
      Clock clock) {
    return new PushService(database, tables, stats, records, events, clock);
  }

  @Bean
  PullService pullService(SqliteDatabase database, SyncTables tables) {
    return new PullService(database, tables);
  }

  @Bean
  UserLifecycle userLifecycle(
      SqliteDatabase database, SyncTables tables, UserStatsStore stats, Clock clock) {
    return new UserLifecycle(database, tables, stats, clock);
  }

  @Bean
  SyncBackendService syncBackendService(
      PushService push, PullService pull, SyncTables tables, UserStatsStore stats, Clock clock) {
    return new SyncBackendService(push, pull, tables, stats, clock);
  }

  @Bean
  SyncEventSink syncEventSink(UserLifecycle lifecycle) {
    return new SyncEventSink(lifecycle);
  }
}

package com.learninge.server.common.sqlite;

import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.ops.BackgroundTasks;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phần chung của hai dịch vụ giữ SQLite (auth, sync). Dịch vụ khai báo bean {@link SqliteDatabase}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackupProperties.class)
public class SqliteConfiguration {
    private static final Duration CHECK_PERIOD = Duration.ofMinutes(10);

    @Bean
    SqliteHealthIndicator sqliteHealthIndicator(SqliteDatabase database) {
        return new SqliteHealthIndicator(database);
    }

    @Bean
    ErrorMappingInterceptor.Translator sqliteErrorTranslator() {
        return SqliteErrors::translate;
    }

    @Bean
    SqliteBackups sqliteBackups(
            SqliteDatabase database,
            @Value("${spring.application.name}") String service,
            BackupProperties properties,
            BackgroundTasks tasks,
            Clock clock) {
        SqliteBackups backups = new SqliteBackups(database, service, clock);
        if (!properties.dir().isBlank()) {
            Path directory = Path.of(properties.dir());
            Duration interval = Duration.ofHours(properties.intervalHours());
            tasks.every(
                    "backup",
                    CHECK_PERIOD,
                    () -> backups.backupIfDue(directory, interval, properties.keep()));
        }
        return backups;
    }

    @Bean
    SqliteBackupCommand backupCommand(SqliteBackups backups) {
        return new SqliteBackupCommand(backups);
    }
}

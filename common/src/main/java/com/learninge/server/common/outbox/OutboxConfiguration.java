package com.learninge.server.common.outbox;

import com.learninge.server.common.config.LearningeProperties;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.ops.BackgroundTasks;
import com.learninge.server.common.sqlite.SqliteDatabase;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox cho dịch vụ có SQLite (auth, sync). Dịch vụ khai báo {@link SqliteDatabase} và nguồn.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
    private static final Logger log = LoggerFactory.getLogger(OutboxConfiguration.class);
    private static final long BACKLOG_WARNING = 10_000;
    private static final Duration STUCK_WARNING = Duration.ofMinutes(5);

    /**
     * Tên dịch vụ phát ({@code auth} hoặc {@code sync}); quyết định relay phục vụ đích nào.
     */
    public record EventSource(String name) {
    }

    @Bean
    OutboxStore outboxStore(SqliteDatabase database) {
        return new OutboxStore(database);
    }

    @Bean
    @ConditionalOnMissingBean
    EventPublisher eventPublisher(InternalChannels channels) {
        return new GrpcEventPublisher(channels);
    }

    @Bean
    OutboxRelay outboxRelay(
            OutboxStore store,
            EventPublisher publisher,
            Clock clock,
            OutboxProperties properties,
            LearningeProperties common,
            EventSource source,
            BackgroundTasks tasks) {
        Backoff backoff =
                new Backoff(
                        properties.maxBackoffSecs() * 1000L, () -> ThreadLocalRandom.current().nextDouble());
        List<Destination> destinations =
                Arrays.stream(EventType.values())
                        .filter(type -> type.source().equals(source.name()))
                        .flatMap(type -> type.destinations().stream())
                        .distinct()
                        .toList();
        scheduleHousekeeping(tasks, store, clock, Duration.ofDays(properties.retentionDays()));
        OutboxDrainer drainer = new OutboxDrainer(store, publisher, backoff, clock, properties.batch());
        return new OutboxRelay(drainer, destinations, common.backgroundEnabled());
    }

    @Bean
    OutboxWriter outboxWriter(OutboxStore store, Clock clock, OutboxRelay relay) {
        return new OutboxWriter(store, clock, relay::wakeUp);
    }

    @Bean
    OutboxRequeueCommand outboxRequeueCommand(OutboxStore store, Clock clock) {
        return new OutboxRequeueCommand(store, clock);
    }

    private static void scheduleHousekeeping(
            BackgroundTasks tasks, OutboxStore store, Clock clock, Duration retention) {
        tasks.every(
                "outbox-cleanup",
                Duration.ofHours(1),
                () -> store.deletePublishedBefore(clock.millis() - retention.toMillis()));
        tasks.every("outbox-backlog", Duration.ofMinutes(1), () -> warnBacklog(store, clock));
    }

    private static void warnBacklog(OutboxStore store, Clock clock) {
        for (Destination destination : Destination.values()) {
            long pending = store.countPending(destination);
            long ageMillis = store.oldestPending(destination).map(at -> clock.millis() - at).orElse(0L);
            if (pending > BACKLOG_WARNING || ageMillis > STUCK_WARNING.toMillis()) {
                log.warn(
                        "outbox_pending destination={} count={} oldest_seconds={}",
                        destination.id(),
                        pending,
                        ageMillis / 1000);
            }
        }
    }
}

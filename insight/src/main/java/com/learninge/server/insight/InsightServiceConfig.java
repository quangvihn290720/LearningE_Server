package com.learninge.server.insight;

import com.learninge.server.common.config.InternalServerConfiguration;
import com.learninge.server.common.mongo.CollectionSpec;
import com.learninge.server.common.mongo.MongoConfiguration;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.common.ops.OpsCommand;
import com.learninge.server.insight.grpc.InsightBackendService;
import com.learninge.server.insight.grpc.InsightEventSink;
import com.learninge.server.insight.service.EventRecorder;
import com.learninge.server.insight.service.ReportCommand;
import com.learninge.server.insight.store.AnalyticsStore;
import com.learninge.server.insight.store.AuditStore;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Dây nối của dịch vụ insight, gồm các index TTL tự xóa dữ liệu cũ.
 */
@Configuration(proxyBeanMethods = false)
@Import({InternalServerConfiguration.class, MongoConfiguration.class})
public class InsightServiceConfig {

    @Bean
    MongoSchema mongoSchema() {
        return new MongoSchema(
                InsightApplication.NAME,
                List.of(
                        CollectionSpec.of(
                                "audit_events",
                                ttl("occurred_at", "ttl_audit_90d", Duration.ofDays(90)),
                                sparse("user_hmac", "ix_audit_user"),
                                new IndexModel(
                                        Indexes.compoundIndex(
                                                Indexes.ascending("type"), Indexes.descending("occurred_at")),
                                        new IndexOptions().name("ix_audit_type"))),
                        CollectionSpec.of(
                                "request_logs",
                                ttl("ts", "ttl_reqlog_7d", Duration.ofDays(7)),
                                sparse("user_hmac", "ix_reqlog_user")),
                        CollectionSpec.of("analytics_daily"),
                        CollectionSpec.of(
                                "analytics_daily_users",
                                ttl("expire_at", "ttl_dau_35d", Duration.ZERO),
                                new IndexModel(Indexes.ascending("_id.u"), new IndexOptions().name("ix_dau_user"))),
                        CollectionSpec.of("deleted_users", ttl("expire_at", "ttl_deleted", Duration.ZERO))));
    }

    private static IndexModel ttl(String field, String name, Duration after) {
        return new IndexModel(
                Indexes.ascending(field),
                new IndexOptions().name(name).expireAfter(after.toSeconds(), TimeUnit.SECONDS));
    }

    private static IndexModel sparse(String field, String name) {
        return new IndexModel(Indexes.ascending(field), new IndexOptions().name(name).sparse(true));
    }

    @Bean
    AuditStore auditStore(MongoDatabase database) {
        return new AuditStore(database);
    }

    @Bean
    AnalyticsStore analyticsStore(MongoDatabase database) {
        return new AnalyticsStore(database);
    }

    @Bean
    EventRecorder eventRecorder(AuditStore audit, AnalyticsStore analytics, Clock clock) {
        return new EventRecorder(audit, analytics, clock);
    }

    @Bean
    InsightBackendService insightBackendService(AuditStore audit, AnalyticsStore analytics) {
        return new InsightBackendService(audit, analytics);
    }

    @Bean
    ReportCommand reportCommand(AnalyticsStore analytics, AuditStore audit, Clock clock) {
        return new ReportCommand(analytics, audit, clock, OpsCommand.stdout());
    }

    @Bean
    InsightEventSink insightEventSink(EventRecorder recorder) {
        return new InsightEventSink(recorder);
    }
}

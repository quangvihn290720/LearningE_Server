package com.learninge.server.edge;

import com.learninge.server.common.config.AppEnv;
import com.learninge.server.common.config.CommonConfiguration;
import com.learninge.server.common.config.LearningeProperties;
import com.learninge.server.common.grpc.ConcurrencyLimitInterceptor;
import com.learninge.server.common.grpc.DeadlineCapInterceptor;
import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.grpc.InterceptorOrder;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.grpc.RequestIdServerInterceptor;
import com.learninge.server.common.ops.BackgroundTasks;
import com.learninge.server.edge.forward.AuthForwarder;
import com.learninge.server.edge.forward.ContentForwarder;
import com.learninge.server.edge.forward.GetMeComposer;
import com.learninge.server.edge.forward.LeaderboardForwarder;
import com.learninge.server.edge.forward.RpcDeadlines;
import com.learninge.server.edge.forward.SyncForwarder;
import com.learninge.server.edge.health.EdgeHealth;
import com.learninge.server.edge.log.RequestLogShipper;
import com.learninge.server.edge.security.AuthenticationInterceptor;
import com.learninge.server.edge.security.ClientIpResolver;
import com.learninge.server.edge.security.ClientMetadataScrubInterceptor;
import com.learninge.server.edge.security.ClientVersionInterceptor;
import com.learninge.server.edge.security.IpRateLimitInterceptor;
import com.learninge.server.edge.security.PayloadLimitInterceptor;
import com.learninge.server.edge.security.TokenAuthenticator;
import com.learninge.server.edge.security.TokenRateLimitInterceptor;
import io.grpc.BindableService;
import io.grpc.protobuf.services.HealthStatusManager;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * Dây nối của edge. Các interceptor xếp theo thứ tự chạy, từ ngoài vào trong.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EdgeProperties.class)
@Import(CommonConfiguration.class)
public class EdgeServiceConfig {
    private static final int MAX_CONCURRENT = 64;
    private static final Duration MAX_QUEUE_WAIT = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(EdgeServiceConfig.class);

    public EdgeServiceConfig(EdgeProperties edge, LearningeProperties common) {
        if (edge.reflection() && common.env() == AppEnv.PROD) {
            throw new IllegalStateException("LEARNINGE_GRPC_REFLECTION chỉ được bật khi APP_ENV=dev");
        }
        if (common.env() == AppEnv.PROD && !edge.trustProxy()) {
            log.warn("EDGE_TRUST_PROXY=false: nếu đứng sau proxy thì mọi người dùng bị tính chung một IP");
        }
    }

    // --- Interceptor ------------------------------------------------------------------------------

    @Bean
    @Order(InterceptorOrder.TRUST_BOUNDARY)
    @GlobalServerInterceptor
    ClientMetadataScrubInterceptor scrubInterceptor() {
        return new ClientMetadataScrubInterceptor();
    }

    @Bean
    @Order(InterceptorOrder.REQUEST_ID)
    @GlobalServerInterceptor
    RequestIdServerInterceptor requestIdInterceptor(
            LearningeProperties common, RequestLogShipper shipper, Clock clock) {
        return new RequestIdServerInterceptor(
                headers -> {
                    headers.put(MetadataKeys.SERVER_VERSION, common.serverVersion());
                    headers.put(MetadataKeys.SERVER_TIME_MS, Long.toString(clock.millis()));
                },
                shipper);
    }

    @Bean
    @Order(InterceptorOrder.CLIENT_VERSION)
    @GlobalServerInterceptor
    ClientVersionInterceptor clientVersionInterceptor(EdgeProperties edge) {
        return new ClientVersionInterceptor(edge.minClient());
    }

    @Bean
    @Order(InterceptorOrder.DEADLINE_CAP)
    @GlobalServerInterceptor
    DeadlineCapInterceptor deadlineCapInterceptor(ScheduledExecutorService deadlineScheduler) {
        return new DeadlineCapInterceptor(RpcDeadlines::inboundCap, deadlineScheduler);
    }

    @Bean
    @Order(InterceptorOrder.IP_RATE_LIMIT)
    @GlobalServerInterceptor
    IpRateLimitInterceptor ipRateLimitInterceptor(ClientIpResolver ips, Clock clock) {
        return new IpRateLimitInterceptor(ips, clock);
    }

    @Bean
    @Order(InterceptorOrder.AUTHENTICATION)
    @GlobalServerInterceptor
    AuthenticationInterceptor authenticationInterceptor(
            TokenAuthenticator tokens, ClientIpResolver ips) {
        return new AuthenticationInterceptor(tokens, ips);
    }

    @Bean
    @Order(InterceptorOrder.TOKEN_RATE_LIMIT)
    @GlobalServerInterceptor
    TokenRateLimitInterceptor tokenRateLimitInterceptor(Clock clock) {
        return new TokenRateLimitInterceptor(clock);
    }

    @Bean
    @Order(InterceptorOrder.PAYLOAD_LIMIT)
    @GlobalServerInterceptor
    PayloadLimitInterceptor payloadLimitInterceptor() {
        return new PayloadLimitInterceptor();
    }

    @Bean
    @Order(InterceptorOrder.CONCURRENCY)
    @GlobalServerInterceptor
    ConcurrencyLimitInterceptor concurrencyLimitInterceptor() {
        return new ConcurrencyLimitInterceptor(MAX_CONCURRENT, MAX_QUEUE_WAIT);
    }

    @Bean
    @Order(InterceptorOrder.ERROR_MAPPING)
    @GlobalServerInterceptor
    ErrorMappingInterceptor errorMappingInterceptor() {
        return new ErrorMappingInterceptor(error -> null);
    }

    // --- Thành phần -------------------------------------------------------------------------------

    @Bean
    ClientIpResolver clientIpResolver(EdgeProperties edge) {
        return new ClientIpResolver(
                edge.trustProxy(), edge.trustedProxies(), edge.clientIpHeader());
    }

    @Bean
    TokenAuthenticator tokenAuthenticator(
            InternalChannels channels, EdgeProperties edge, Clock clock) {
        return new TokenAuthenticator(channels, Duration.ofSeconds(edge.tokenCacheSecs()), clock);
    }

    @Bean
    RequestLogShipper requestLogShipper(
            InternalChannels channels, LearningeProperties common, Clock clock) {
        return new RequestLogShipper(channels, clock, common.backgroundEnabled());
    }

    @Bean
    EdgeHealth edgeHealth(InternalChannels channels, BackgroundTasks tasks) {
        EdgeHealth health = new EdgeHealth(channels, new HealthStatusManager());
        tasks.every("edge-health", EdgeHealth.PERIOD, health::refresh);
        return health;
    }

    @Bean
    BindableService healthService(EdgeHealth health) {
        return health.grpcService();
    }

    @Bean
    HealthIndicator coreHealthIndicator(EdgeHealth health) {
        return health::indicator;
    }

    // --- Chuyển tiếp ------------------------------------------------------------------------------

    @Bean
    GetMeComposer getMeComposer(InternalChannels channels) {
        return new GetMeComposer(channels);
    }

    @Bean
    AuthForwarder authForwarder(
            InternalChannels channels, TokenAuthenticator tokens, GetMeComposer getMe) {
        return new AuthForwarder(channels, tokens, getMe);
    }

    @Bean
    SyncForwarder syncForwarder(InternalChannels channels) {
        return new SyncForwarder(channels);
    }

    @Bean
    LeaderboardForwarder leaderboardForwarder(InternalChannels channels) {
        return new LeaderboardForwarder(channels);
    }

    @Bean
    ContentForwarder contentForwarder(InternalChannels channels) {
        return new ContentForwarder(channels);
    }
}

package com.learninge.server.common.config;

import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.InternalClientInterceptor;
import com.learninge.server.common.ops.BackgroundTasks;
import io.grpc.netty.NettyServerBuilder;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.ServerBuilderCustomizer;

/**
 * Bean dùng chung cho cả 6 dịch vụ.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LearningeProperties.class)
public class CommonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Thread pool cố định thay cho executor không giới hạn của grpc-java.
     */
    @Bean
    GrpcServerExecutorProvider grpcExecutor(LearningeProperties properties) {
        var executor =
                Executors.newFixedThreadPool(
                        properties.grpcThreads(), Thread.ofPlatform().name("grpc-", 0).daemon().factory());
        return () -> executor;
    }

    /**
     * Giới hạn HTTP/2 mà property của Spring gRPC không phủ (TM-44).
     */
    @Bean
    ServerBuilderCustomizer<NettyServerBuilder> nettyLimits() {
        return builder -> builder.maxConcurrentCallsPerConnection(100);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService deadlineScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("deadline").daemon().factory());
    }

    @Bean
    BackgroundTasks backgroundTasks(LearningeProperties properties) {
        return new BackgroundTasks(properties.backgroundEnabled());
    }

    @Bean
    InternalChannels internalChannels(LearningeProperties properties) {
        Secret token = SecretKeys.internalToken(properties.internalToken());
        return new InternalChannels(properties.services(), new InternalClientInterceptor(token));
    }
}

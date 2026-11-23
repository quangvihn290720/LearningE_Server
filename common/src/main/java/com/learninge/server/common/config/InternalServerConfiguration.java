package com.learninge.server.common.config;

import com.learninge.server.common.grpc.DeadlineCapInterceptor;
import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.grpc.InterceptorOrder;
import com.learninge.server.common.grpc.InternalAuthServerInterceptor;
import com.learninge.server.common.grpc.RequestIdServerInterceptor;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Chuỗi interceptor của 5 dịch vụ nội bộ. Mỗi dịch vụ tự khai báo bean {@code Translator}. */
@Configuration(proxyBeanMethods = false)
@Import(CommonConfiguration.class)
public class InternalServerConfiguration {
  private static final Duration INTERNAL_CAP = Duration.ofSeconds(20);

  @Bean
  @Order(InterceptorOrder.TRUST_BOUNDARY)
  @GlobalServerInterceptor
  InternalAuthServerInterceptor internalAuthInterceptor(LearningeProperties properties) {
    return new InternalAuthServerInterceptor(SecretKeys.internalToken(properties.internalToken()));
  }

  @Bean
  @Order(InterceptorOrder.REQUEST_ID)
  @GlobalServerInterceptor
  RequestIdServerInterceptor requestIdInterceptor() {
    return new RequestIdServerInterceptor(headers -> {}, (info, code) -> {});
  }

  @Bean
  @Order(InterceptorOrder.DEADLINE_CAP)
  @GlobalServerInterceptor
  DeadlineCapInterceptor deadlineCapInterceptor(ScheduledExecutorService deadlineScheduler) {
    return new DeadlineCapInterceptor(method -> INTERNAL_CAP, deadlineScheduler);
  }

  @Bean
  @Order(InterceptorOrder.ERROR_MAPPING)
  @GlobalServerInterceptor
  ErrorMappingInterceptor errorMappingInterceptor(ErrorMappingInterceptor.Translator translator) {
    return new ErrorMappingInterceptor(translator);
  }
}

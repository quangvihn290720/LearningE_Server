package com.learninge.server.edge.health;

import com.learninge.proto.app.v1.ContentServiceGrpc;
import com.learninge.proto.app.v1.LeaderboardServiceGrpc;
import com.learninge.server.common.grpc.InternalChannels;
import io.grpc.BindableService;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.health.contributor.Health;

/**
 * EP-01. Trạng thái chung = lõi (auth + sync); bảng xếp hạng và nội dung có trạng thái riêng nên
 * Mongo ngừng không làm edge báo hỏng.
 */
public final class EdgeHealth {
  public static final Duration PERIOD = Duration.ofSeconds(1);

  private static final String OVERALL = HealthStatusManager.SERVICE_NAME_ALL_SERVICES;

  private final InternalChannels channels;
  private final HealthStatusManager manager;
  private volatile boolean coreServing;

  public EdgeHealth(InternalChannels channels, HealthStatusManager manager) {
    this.channels = channels;
    this.manager = manager;
    publish(OVERALL, false);
    publish(LeaderboardServiceGrpc.SERVICE_NAME, false);
    publish(ContentServiceGrpc.SERVICE_NAME, false);
  }

  public BindableService grpcService() {
    return manager.getHealthService();
  }

  public void refresh() {
    coreServing = isServing("auth") && isServing("sync");
    publish(OVERALL, coreServing);
    publish(LeaderboardServiceGrpc.SERVICE_NAME, isServing("gamification"));
    publish(ContentServiceGrpc.SERVICE_NAME, isServing("content"));
  }

  public Health indicator() {
    return coreServing ? Health.up().build() : Health.down().build();
  }

  private void publish(String service, boolean serving) {
    manager.setStatus(service, serving ? ServingStatus.SERVING : ServingStatus.NOT_SERVING);
  }

  private boolean isServing(String service) {
    try {
      return HealthGrpc.newBlockingStub(channels.to(service))
              .withDeadlineAfter(1, TimeUnit.SECONDS)
              .check(HealthCheckRequest.getDefaultInstance())
              .getStatus()
          == ServingStatus.SERVING;
    } catch (StatusRuntimeException e) {
      return false;
    }
  }
}

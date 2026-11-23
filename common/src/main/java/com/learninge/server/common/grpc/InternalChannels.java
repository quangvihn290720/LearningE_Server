package com.learninge.server.common.grpc;

import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kênh h2c tới dịch vụ nội bộ, mỗi dịch vụ một kênh dùng chung. Kết nối lười nên dịch vụ lên được
 * dù đích chưa sẵn sàng. Tắt tự thử lại: app và relay có chính sách thử lại riêng.
 */
public final class InternalChannels implements AutoCloseable {
  private final Map<String, String> addresses;
  private final ClientInterceptor interceptor;
  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

  public InternalChannels(Map<String, String> addresses, ClientInterceptor interceptor) {
    this.addresses = Map.copyOf(addresses);
    this.interceptor = interceptor;
  }

  public Channel to(String service) {
    return channels.computeIfAbsent(service, this::open);
  }

  private ManagedChannel open(String service) {
    String address = addresses.get(service);
    if (address == null) {
      throw new IllegalStateException("Chưa cấu hình learninge.services." + service);
    }
    return NettyChannelBuilder.forTarget(address)
        .usePlaintext()
        .disableRetry()
        .intercept(interceptor)
        .build();
  }

  @Override
  public void close() {
    channels.values().forEach(ManagedChannel::shutdown);
    for (ManagedChannel channel : channels.values()) {
      awaitTermination(channel);
    }
  }

  private static void awaitTermination(ManagedChannel channel) {
    try {
      if (channel.awaitTermination(3, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    channel.shutdownNow();
  }
}

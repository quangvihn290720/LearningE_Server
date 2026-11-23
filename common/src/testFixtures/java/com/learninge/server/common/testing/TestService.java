package com.learninge.server.common.testing;

import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.MetadataKeys;
import com.learninge.server.common.ops.ServiceLauncher;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/** Chạy một dịch vụ thật (Spring + Netty, cổng ngẫu nhiên) và gọi nó qua mạng loopback. */
public final class TestService implements AutoCloseable {
  public static final String INTERNAL_TOKEN = "test-internal-token-0123456789abcdef";

  private final ConfigurableApplicationContext context;
  private final ManagedChannel channel;

  private TestService(ConfigurableApplicationContext context) {
    this.context = context;
    this.channel = NettyChannelBuilder.forAddress("127.0.0.1", port()).usePlaintext().build();
  }

  public static TestService start(
      Class<?> application, String service, Clock clock, String... properties) {
    List<String> args = new ArrayList<>(List.of(properties));
    args.add("--spring.main.web-application-type=none");
    args.add("--spring.grpc.server.port=0");
    args.add("--learninge.internal-token=" + INTERNAL_TOKEN);
    return new TestService(
        ServiceLauncher.start(
            application,
            service,
            builder ->
                builder.initializers(
                    ctx ->
                        ((GenericApplicationContext) ctx).registerBean(Clock.class, () -> clock)),
            args.toArray(String[]::new)));
  }

  public <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  public int port() {
    return context.getBean(GrpcServerLifecycle.class).getPort();
  }

  /** Kênh không có khóa nội bộ. */
  public Channel anonymous() {
    return channel;
  }

  /** Kênh như edge gọi, không kèm người dùng. */
  public Channel internal() {
    return with(MetadataKeys.INTERNAL_AUTH, INTERNAL_TOKEN);
  }

  /** Kênh như edge gọi thay cho một người dùng. */
  public Channel as(String userId) {
    Metadata headers = new Metadata();
    headers.put(MetadataKeys.INTERNAL_AUTH, INTERNAL_TOKEN);
    headers.put(MetadataKeys.USER_ID, userId);
    return with(headers);
  }

  public Channel with(Metadata.Key<String> key, String value) {
    Metadata headers = new Metadata();
    headers.put(key, value);
    return with(headers);
  }

  public Channel with(Metadata headers) {
    return ClientInterceptors.intercept(
        channel, MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  /** Khẳng định lời gọi thất bại đúng mã gRPC và {@code error-code}; trả trailer để kiểm tiếp. */
  public static Metadata assertFails(ThrowingCallable call, Status.Code status, ErrorCode code) {
    StatusRuntimeException error =
        Assertions.catchThrowableOfType(StatusRuntimeException.class, call);
    Assertions.assertThat(error).as("lời gọi phải thất bại").isNotNull();
    Metadata trailers = error.getTrailers();
    Assertions.assertThat(error.getStatus().getCode())
        .as("%s %s", error.getStatus(), trailers)
        .isEqualTo(status);
    Assertions.assertThat(trailers.get(MetadataKeys.ERROR_CODE)).isEqualTo(code.name());
    return trailers;
  }

  public static Metadata assertFails(ThrowingCallable call, ErrorCode code) {
    return assertFails(call, code.status(), code);
  }

  /** Thiếu hoặc sai khóa nội bộ: UNAUTHENTICATED, không có bất kỳ chi tiết nào. */
  public static void assertInternalTokenRequired(ThrowingCallable call) {
    StatusRuntimeException error =
        Assertions.catchThrowableOfType(StatusRuntimeException.class, call);
    Assertions.assertThat(error).as("lời gọi phải thất bại").isNotNull();
    Assertions.assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    Assertions.assertThat(error.getTrailers().containsKey(MetadataKeys.ERROR_CODE)).isFalse();
  }

  @Override
  public void close() {
    channel.shutdownNow();
    context.close();
  }
}

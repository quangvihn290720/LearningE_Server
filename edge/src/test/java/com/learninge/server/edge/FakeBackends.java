package com.learninge.server.edge;

import com.google.protobuf.Message;
import com.learninge.proto.app.v1.AuthSession;
import com.learninge.proto.app.v1.User;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.ContentBackendGrpc;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.proto.internal.v1.GetProfileResponse;
import com.learninge.proto.internal.v1.GetStatsResponse;
import com.learninge.proto.internal.v1.GetStreakResponse;
import com.learninge.proto.internal.v1.InsightBackendGrpc;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.proto.internal.v1.TokenValidatorGrpc;
import com.learninge.proto.internal.v1.ValidateTokenRequest;
import com.learninge.proto.internal.v1.ValidateTokenResponse;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.GrpcErrors;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Năm dịch vụ nội bộ giả trên một cổng: ghi lại mọi metadata nhận được, trả phản hồi mặc định hoặc
 * phản hồi/lỗi do test đặt.
 */
final class FakeBackends implements AutoCloseable {
  /** Một lời gọi mà dịch vụ phía sau thật sự nhận được. */
  record Received(String method, Metadata headers, Message request, long deadlineMs) {}

  private static final Context.Key<Metadata> HEADERS = Context.key("headers");

  final List<Received> received = new CopyOnWriteArrayList<>();
  private final Map<String, Function<Message, Message>> handlers = new ConcurrentHashMap<>();
  private final Map<com.google.protobuf.ByteString, ValidateTokenResponse> tokens =
      new ConcurrentHashMap<>();
  private final Server server;

  FakeBackends() throws IOException {
    NettyServerBuilder builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0));
    for (ServiceDescriptor service :
        List.of(
            TokenValidatorGrpc.getServiceDescriptor(),
            AuthBackendGrpc.getServiceDescriptor(),
            SyncBackendGrpc.getServiceDescriptor(),
            ContentBackendGrpc.getServiceDescriptor(),
            GamificationBackendGrpc.getServiceDescriptor(),
            InsightBackendGrpc.getServiceDescriptor())) {
      builder.addService(fake(service));
    }
    builder.addService(new HealthStatusManager().getHealthService());
    builder.intercept(new CaptureHeaders());
    server = builder.build().start();

    on(
        TokenValidatorGrpc.getValidateTokenMethod(),
        request -> {
          ValidateTokenResponse found =
              tokens.get(((ValidateTokenRequest) request).getTokenSha256());
          if (found == null) {
            throw AppException.of(ErrorCode.UNAUTHENTICATED);
          }
          return found;
        });
    on(
        AuthBackendGrpc.getGetProfileMethod(),
        r -> GetProfileResponse.newBuilder().setUser(user("Minh")).build());
    on(
        SyncBackendGrpc.getGetStreakMethod(),
        r ->
            GetStreakResponse.newBuilder()
                .setTotalXp(900)
                .setCurrentStreak(5)
                .setLongestStreak(9)
                .setLastStudyDate("2026-11-12")
                .build());
    on(
        GamificationBackendGrpc.getGetStatsMethod(),
        r -> GetStatsResponse.newBuilder().setTotalXp(800).setWeekXp(120).build());
  }

  static User user(String name) {
    return User.newBuilder().setId(EdgeServiceTest.USER).setDisplayName(name).build();
  }

  static AuthSession session() {
    return AuthSession.newBuilder().setToken("new").setUser(user("Minh")).build();
  }

  int port() {
    return server.getPort();
  }

  void allow(com.google.protobuf.ByteString sha256, String userId, long expiresAtMs) {
    tokens.put(
        sha256,
        ValidateTokenResponse.newBuilder()
            .setUserId(userId)
            .setUserHmac("0123456789abcdef0123456789abcdef")
            .setExpiresAtMs(expiresAtMs)
            .build());
  }

  void revokeAll() {
    tokens.clear();
  }

  void on(MethodDescriptor<?, ?> method, Function<Message, Message> handler) {
    handlers.put(method.getFullMethodName(), handler);
  }

  void fail(MethodDescriptor<?, ?> method, Status status) {
    on(
        method,
        r -> {
          throw status.asRuntimeException();
        });
  }

  void fail(MethodDescriptor<?, ?> method, AppException error) {
    on(
        method,
        r -> {
          throw error;
        });
  }

  void delay(MethodDescriptor<?, ?> method, long millis, Message response) {
    on(
        method,
        r -> {
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return response;
        });
  }

  List<Received> calls(MethodDescriptor<?, ?> method) {
    return received.stream().filter(r -> r.method().equals(method.getFullMethodName())).toList();
  }

  private ServerServiceDefinition fake(ServiceDescriptor service) {
    ServerServiceDefinition.Builder definition = ServerServiceDefinition.builder(service);
    for (MethodDescriptor<?, ?> method : service.getMethods()) {
      addMethod(definition, method);
    }
    return definition.build();
  }

  @SuppressWarnings("unchecked")
  private void addMethod(
      ServerServiceDefinition.Builder definition, MethodDescriptor<?, ?> method) {
    MethodDescriptor<Message, Message> typed = (MethodDescriptor<Message, Message>) method;
    Message defaultResponse =
        ((MethodDescriptor.PrototypeMarshaller<Message>) typed.getResponseMarshaller())
            .getMessagePrototype();
    ServerCallHandler<Message, Message> handler =
        ServerCalls.asyncUnaryCall(
            (request, observer) -> {
              long deadline =
                  Context.current().getDeadline() == null
                      ? -1
                      : Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
              received.add(
                  new Received(method.getFullMethodName(), HEADERS.get(), request, deadline));
              try {
                observer.onNext(
                    handlers
                        .getOrDefault(method.getFullMethodName(), r -> defaultResponse)
                        .apply(request));
                observer.onCompleted();
              } catch (AppException e) {
                observer.onError(GrpcErrors.status(e).asRuntimeException(GrpcErrors.trailers(e)));
              } catch (StatusRuntimeException e) {
                observer.onError(e);
              }
            });
    definition.addMethod(ServerMethodDefinition.create(typed, handler));
  }

  private static final class CaptureHeaders implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      Metadata copy = new Metadata();
      copy.merge(headers);
      return Contexts.interceptCall(
          Context.current().withValue(HEADERS, copy), call, headers, next);
    }
  }

  @Override
  public void close() {
    server.shutdownNow();
  }
}

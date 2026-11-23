package com.learninge.server.edge.security;

import com.learninge.proto.app.v1.AuthServiceGrpc;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.GrpcErrors;
import com.learninge.server.common.security.SlidingWindowLimiter;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Giới hạn RPC công khai theo IP, trước khi yêu cầu chạm tới auth (NFR-S8). */
public final class IpRateLimitInterceptor implements ServerInterceptor {
  private final ClientIpResolver ips;
  private final Map<String, SlidingWindowLimiter> limiters;

  public IpRateLimitInterceptor(ClientIpResolver ips, Clock clock) {
    this.ips = ips;
    SlidingWindowLimiter reset = new SlidingWindowLimiter(5, Duration.ofMinutes(1), clock);
    this.limiters =
        Map.of(
            AuthServiceGrpc.getLoginMethod().getFullMethodName(),
            new SlidingWindowLimiter(5, Duration.ofMinutes(1), clock),
            AuthServiceGrpc.getRegisterMethod().getFullMethodName(),
            new SlidingWindowLimiter(10, Duration.ofHours(1), clock),
            AuthServiceGrpc.getGetResetQuestionMethod().getFullMethodName(),
            reset,
            AuthServiceGrpc.getResetPasswordMethod().getFullMethodName(),
            reset);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    SlidingWindowLimiter limiter = limiters.get(AppMethods.name(call));
    if (limiter != null) {
      long wait = limiter.tryAcquire(ips.resolve(call.getAttributes(), headers));
      if (wait > 0) {
        return GrpcErrors.reject(call, AppException.retryAfter(ErrorCode.RATE_LIMITED, wait));
      }
    }
    return next.startCall(call, headers);
  }
}

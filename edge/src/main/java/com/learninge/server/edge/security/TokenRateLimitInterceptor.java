package com.learninge.server.edge.security;

import com.learninge.proto.app.v1.SyncServiceGrpc;
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
import java.util.HexFormat;

/** 30 Push và 120 lời gọi khác mỗi phút cho mỗi token. */
public final class TokenRateLimitInterceptor implements ServerInterceptor {
  private final SlidingWindowLimiter push;
  private final SlidingWindowLimiter others;

  public TokenRateLimitInterceptor(Clock clock) {
    this.push = new SlidingWindowLimiter(30, Duration.ofMinutes(1), clock);
    this.others = new SlidingWindowLimiter(120, Duration.ofMinutes(1), clock);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Identity identity = Identity.KEY.get();
    if (identity != null) {
      boolean isPush = AppMethods.is(call, SyncServiceGrpc.getPushMethod());
      String key = HexFormat.of().formatHex(identity.tokenSha256().toByteArray());
      long wait = (isPush ? push : others).tryAcquire(key);
      if (wait > 0) {
        return GrpcErrors.reject(call, AppException.retryAfter(ErrorCode.RATE_LIMITED, wait));
      }
    }
    return next.startCall(call, headers);
  }
}

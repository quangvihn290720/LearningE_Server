package com.learninge.server.edge.security;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.CallInfo;
import com.learninge.server.common.grpc.GrpcErrors;
import com.learninge.server.common.grpc.MetadataKeys;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/** Bản app thiếu hoặc quá cũ {@code x-client-version} phải cập nhật. */
public final class ClientVersionInterceptor implements ServerInterceptor {
  private final int minimum;

  public ClientVersionInterceptor(int minimum) {
    this.minimum = minimum;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (AppMethods.isInfrastructure(call)) {
      return next.startCall(call, headers);
    }
    int version = parse(headers.get(MetadataKeys.CLIENT_VERSION));
    if (version < minimum) {
      return GrpcErrors.reject(call, AppException.of(ErrorCode.UPGRADE_REQUIRED));
    }
    CallInfo.KEY.get().clientVersion(version);
    return next.startCall(call, headers);
  }

  private static int parse(String value) {
    try {
      return value == null ? -1 : Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}

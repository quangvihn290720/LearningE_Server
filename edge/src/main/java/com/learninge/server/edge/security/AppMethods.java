package com.learninge.server.edge.security;

import com.learninge.proto.app.v1.AuthServiceGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import java.util.Set;

/** Phân loại RPC. Mặc định là <b>cần token</b>: RPC mới thêm vào proto không tự thành công khai. */
public final class AppMethods {
  private static final String APP_PREFIX = "learninge.";

  private static final Set<String> PUBLIC =
      Set.of(
          AuthServiceGrpc.getRegisterMethod().getFullMethodName(),
          AuthServiceGrpc.getLoginMethod().getFullMethodName(),
          AuthServiceGrpc.getGetResetQuestionMethod().getFullMethodName(),
          AuthServiceGrpc.getResetPasswordMethod().getFullMethodName());

  private AppMethods() {}

  /** Không phải dịch vụ của app: Health, reflection. */
  static boolean isInfrastructure(ServerCall<?, ?> call) {
    return !name(call).startsWith(APP_PREFIX);
  }

  static boolean needsToken(ServerCall<?, ?> call) {
    return !isInfrastructure(call) && !PUBLIC.contains(name(call));
  }

  static boolean is(ServerCall<?, ?> call, MethodDescriptor<?, ?> method) {
    return name(call).equals(method.getFullMethodName());
  }

  static String name(ServerCall<?, ?> call) {
    return call.getMethodDescriptor().getFullMethodName();
  }
}

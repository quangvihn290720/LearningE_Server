package com.learninge.server.common.grpc;

import com.learninge.server.common.config.Secret;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.id.Uuids;
import com.learninge.server.common.security.Digests;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cổng tin cậy của 5 dịch vụ nội bộ (D-09): kiểm {@code x-internal-auth} trước mọi interceptor
 * khác, đúng khóa rồi mới đọc {@code x-user-id}. Chỉ {@code Health/Check} được miễn.
 */
public final class InternalAuthServerInterceptor implements ServerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(InternalAuthServerInterceptor.class);
  private static final String HEALTH_CHECK = "grpc.health.v1.Health/Check";

  private final byte[] expectedDigest;

  public InternalAuthServerInterceptor(Secret internalToken) {
    this.expectedDigest = digest(internalToken.reveal());
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String rpc = call.getMethodDescriptor().getFullMethodName();
    if (rpc.equals(HEALTH_CHECK)) {
      return next.startCall(call, headers);
    }
    String token = headers.get(MetadataKeys.INTERNAL_AUTH);
    // Băm trước khi so để thời gian không phụ thuộc độ dài hay vị trí byte sai (TM-57).
    if (token == null || !MessageDigest.isEqual(digest(token), expectedDigest)) {
      log.warn(
          "Từ chối lời gọi nội bộ {} từ {}",
          rpc,
          call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
      return GrpcErrors.reject(call, AppException.of(ErrorCode.UNAUTHENTICATED, "Không có quyền"));
    }
    String userId = headers.get(MetadataKeys.USER_ID);
    if (userId == null || !Uuids.isCanonical(userId)) {
      return next.startCall(call, headers);
    }
    Context context = Context.current().withValue(CallerContext.USER_ID, userId);
    return Contexts.interceptCall(context, call, headers, next);
  }

  private static byte[] digest(String value) {
    return Digests.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}

package com.learninge.server.common.grpc;

import com.learninge.server.common.error.AppException;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.util.stream.Collectors;

/** Đổi {@link AppException} thành {@link Status} + trailer theo GRPC-API §Quy ước lỗi. */
public final class GrpcErrors {
  private GrpcErrors() {}

  public static Status status(AppException error) {
    return error.status().toStatus().withDescription(error.getMessage());
  }

  public static Metadata trailers(AppException error) {
    Metadata trailers = new Metadata();
    trailers.put(MetadataKeys.ERROR_CODE, error.code().name());
    if (error.retryAfterSeconds() > 0) {
      trailers.put(MetadataKeys.RETRY_AFTER_SECONDS, Long.toString(error.retryAfterSeconds()));
    }
    if (!error.fields().isEmpty()) {
      String fields =
          error.fields().entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .sorted()
              .collect(Collectors.joining(","));
      trailers.put(MetadataKeys.ERROR_FIELDS, fields);
    }
    return trailers;
  }

  /** Dùng trong interceptor: đóng lời gọi mà không chạy handler. */
  public static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
      ServerCall<ReqT, RespT> call, AppException error) {
    call.close(status(error), trailers(error));
    return new ServerCall.Listener<>() {};
  }
}

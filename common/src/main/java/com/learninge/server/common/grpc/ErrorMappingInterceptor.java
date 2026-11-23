package com.learninge.server.common.grpc;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interceptor trong cùng: mọi ngoại lệ của handler đều thành lỗi có {@code error-code}, không lộ SQL,
 * đường dẫn hay stack trace ra ngoài (TM-22, TM-52).
 */
public final class ErrorMappingInterceptor implements ServerInterceptor {
  /** Đổi lỗi đặc thù của dịch vụ (vd Mongo ngừng) thành {@link AppException}; null = không biết. */
  @FunctionalInterface
  public interface Translator {
    AppException translate(RuntimeException error);
  }

  private static final Logger log = LoggerFactory.getLogger(ErrorMappingInterceptor.class);

  private final Translator translator;

  public ErrorMappingInterceptor(Translator translator) {
    this.translator = translator;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
      @Override
      public void onHalfClose() {
        try {
          super.onHalfClose();
        } catch (RuntimeException e) {
          close(call, e);
        }
      }
    };
  }

  private <ReqT, RespT> void close(ServerCall<ReqT, RespT> call, RuntimeException error) {
    if (error instanceof StatusRuntimeException forwarded) {
      // Edge đã quyết định mã + trailer trả cho app.
      Metadata trailers = forwarded.getTrailers();
      call.close(forwarded.getStatus(), trailers == null ? new Metadata() : trailers);
      return;
    }
    AppException known = error instanceof AppException app ? app : translator.translate(error);
    if (known == null) {
      log.error("Lỗi chưa xử lý trong {}", call.getMethodDescriptor().getFullMethodName(), error);
      known = AppException.of(ErrorCode.INTERNAL_ERROR);
    }
    Status status = GrpcErrors.status(known);
    call.close(status, GrpcErrors.trailers(known));
  }
}

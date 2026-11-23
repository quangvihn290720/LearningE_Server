package com.learninge.server.common.grpc;

import com.learninge.server.common.id.Uuids;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Nhận {@code x-request-id} hợp lệ hoặc sinh mới, đặt vào {@link Context} và MDC, trả lại trong
 * header, rồi ghi một dòng log khi lời gọi kết thúc.
 *
 * <p>gRPC có thể đổi luồng giữa các callback nên MDC được đặt/xóa quanh <b>từng</b> callback.
 */
public final class RequestIdServerInterceptor implements ServerInterceptor {
  /** Được gọi một lần khi lời gọi đóng; edge dùng để gửi request log. */
  @FunctionalInterface
  public interface CallObserver {
    void onClosed(CallInfo info, Status.Code code);
  }

  /** Cho phép dịch vụ thêm header phản hồi (edge: phiên bản và giờ server). */
  @FunctionalInterface
  public interface HeaderDecorator {
    void decorate(Metadata headers);
  }

  private static final Logger log = LoggerFactory.getLogger("rpc");

  private final HeaderDecorator decorator;
  private final CallObserver observer;

  public RequestIdServerInterceptor(HeaderDecorator decorator, CallObserver observer) {
    this.decorator = decorator;
    this.observer = observer;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String received = headers.get(MetadataKeys.REQUEST_ID);
    String requestId =
        received != null && Uuids.isCanonical(received) ? received : UUID.randomUUID().toString();
    CallInfo info = new CallInfo(requestId, call.getMethodDescriptor().getFullMethodName());
    ServerCall<ReqT, RespT> wrapped = new ObservedCall<>(call, info);
    Context context = Context.current().withValue(CallInfo.KEY, info);

    putMdc(info);
    try {
      return new MdcListener<>(Contexts.interceptCall(context, wrapped, headers, next), info);
    } finally {
      MDC.clear();
    }
  }

  private static void putMdc(CallInfo info) {
    MDC.put("request_id", info.requestId());
    MDC.put("rpc", info.rpc());
    if (!info.userHmac().isEmpty()) {
      MDC.put("user_hmac", info.userHmac());
    }
  }

  private final class ObservedCall<ReqT, RespT>
      extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
    private final CallInfo info;
    private boolean headersSent;

    ObservedCall(ServerCall<ReqT, RespT> delegate, CallInfo info) {
      super(delegate);
      this.info = info;
    }

    @Override
    public void sendHeaders(Metadata responseHeaders) {
      headersSent = true;
      addHeaders(responseHeaders);
      super.sendHeaders(responseHeaders);
    }

    @Override
    public void close(Status status, Metadata trailers) {
      if (!headersSent) {
        // Lỗi trước khi có header: gRPC gửi "trailers-only" nên header phải nằm trong trailer.
        addHeaders(trailers);
      }
      super.close(status, trailers);
      log.atInfo()
          .addKeyValue("grpc_code", status.getCode())
          .addKeyValue("duration_ms", info.durationMs())
          .log("{} {}", info.rpc(), status.getCode());
      observer.onClosed(info, status.getCode());
    }

    private void addHeaders(Metadata target) {
      target.put(MetadataKeys.REQUEST_ID, info.requestId());
      decorator.decorate(target);
    }
  }

  private static final class MdcListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final CallInfo info;

    MdcListener(ServerCall.Listener<ReqT> delegate, CallInfo info) {
      super(delegate);
      this.info = info;
    }

    @Override
    public void onMessage(ReqT message) {
      run(() -> super.onMessage(message));
    }

    @Override
    public void onHalfClose() {
      run(super::onHalfClose);
    }

    @Override
    public void onCancel() {
      run(super::onCancel);
    }

    @Override
    public void onComplete() {
      run(super::onComplete);
    }

    @Override
    public void onReady() {
      run(super::onReady);
    }

    private void run(Runnable callback) {
      putMdc(info);
      try {
        callback.run();
      } finally {
        MDC.clear();
      }
    }
  }
}

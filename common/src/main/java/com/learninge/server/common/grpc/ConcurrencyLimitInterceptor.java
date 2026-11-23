package com.learninge.server.common.grpc;

import com.learninge.server.common.error.AppException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Trần số RPC được xử lý cùng lúc; chờ quá lâu thì báo bận thay vì dồn việc vô hạn.
 *
 * <p>Giấy phép chỉ giữ trong {@code onHalfClose} — nơi handler unary thật sự chạy — nên lời gọi
 * đang chờ client gửi dữ liệu không chiếm chỗ. Health không qua giới hạn để không bị đói.
 */
public final class ConcurrencyLimitInterceptor implements ServerInterceptor {
    private static final String HEALTH_PREFIX = "grpc.health.v1.";

    private final Semaphore permits;
    private final Duration maxWait;

    public ConcurrencyLimitInterceptor(int maxConcurrent, Duration maxWait) {
        this.permits = new Semaphore(maxConcurrent);
        this.maxWait = maxWait;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
        if (call.getMethodDescriptor().getFullMethodName().startsWith(HEALTH_PREFIX)) {
            return listener;
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onHalfClose() {
                if (!acquire()) {
                    AppException busy = AppException.busy();
                    call.close(GrpcErrors.status(busy), GrpcErrors.trailers(busy));
                    return;
                }
                try {
                    super.onHalfClose();
                } finally {
                    permits.release();
                }
            }
        };
    }

    private boolean acquire() {
        try {
            return permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

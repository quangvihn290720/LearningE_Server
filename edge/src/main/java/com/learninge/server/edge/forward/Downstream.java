package com.learninge.server.edge.forward;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.MetadataKeys;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Gọi dịch vụ phía sau: deadline = phần còn lại của app − 100 ms, chặn trên theo nhóm; không thử
 * lại (app tự thử lại). Lỗi nghiệp vụ chuyển nguyên, lỗi hạ tầng đổi thành mã chung.
 */
final class Downstream {
    private Downstream() {
    }

    static <S extends AbstractStub<S>> S timed(S stub, Duration cap) {
        long millis = cap.toMillis();
        Deadline inbound = Context.current().getDeadline();
        if (inbound != null) {
            long remaining =
                    inbound.timeRemaining(TimeUnit.MILLISECONDS) - RpcDeadlines.MARGIN.toMillis();
            millis = Math.min(millis, remaining);
        }
        if (millis <= 0) {
            throw Status.DEADLINE_EXCEEDED.asRuntimeException();
        }
        return stub.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }

    static <T> T call(Supplier<T> rpc) {
        try {
            return rpc.get();
        } catch (StatusRuntimeException e) {
            throw translate(e.getStatus(), e.getTrailers());
        }
    }

    static RuntimeException translate(Status status, Metadata trailers) {
        boolean fromLearningE = trailers != null && trailers.containsKey(MetadataKeys.ERROR_CODE);
        return switch (status.getCode()) {
            case DEADLINE_EXCEEDED, CANCELLED -> Status.DEADLINE_EXCEEDED.asRuntimeException();
            case INTERNAL, UNKNOWN, DATA_LOSS -> AppException.of(ErrorCode.INTERNAL_ERROR);
            // Không có error-code = chưa tới nghiệp vụ: mất kết nối, sai khóa nội bộ, lệch phiên bản.
            case UNAUTHENTICATED, UNIMPLEMENTED, PERMISSION_DENIED ->
                    fromLearningE ? forward(status, trailers) : AppException.of(ErrorCode.INTERNAL_ERROR);
            default -> fromLearningE
                    ? forward(status, trailers)
                    : AppException.of(ErrorCode.SERVICE_UNAVAILABLE);
        };
    }

    /**
     * Chỉ chuyển các trailer thuộc hợp đồng với app.
     */
    private static StatusRuntimeException forward(Status status, Metadata trailers) {
        Metadata allowed = new Metadata();
        copy(trailers, allowed, MetadataKeys.ERROR_CODE);
        copy(trailers, allowed, MetadataKeys.ERROR_FIELDS);
        copy(trailers, allowed, MetadataKeys.RETRY_AFTER_SECONDS);
        return status.asRuntimeException(allowed);
    }

    private static void copy(Metadata from, Metadata to, Metadata.Key<String> key) {
        String value = from.get(key);
        if (value != null) {
            to.put(key, value);
        }
    }
}

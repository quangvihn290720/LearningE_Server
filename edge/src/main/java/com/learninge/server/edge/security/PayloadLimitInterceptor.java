package com.learninge.server.edge.security;

import com.google.protobuf.MessageLite;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.grpc.GrpcErrors;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Message quá 1 MiB bị từ chối kèm mã {@code PAYLOAD_TOO_LARGE} (NFR-N7).
 *
 * <p>Trần của tầng vận chuyển là 2 MiB ({@code edge.yml}): quá mức đó grpc-java cắt ngay khi đọc độ
 * dài, không giữ dữ liệu nhưng cũng không gắn được mã lỗi. Lô 500 bản ghi hợp lệ lớn nhất chưa tới
 * 1,5 MiB nên app thật luôn nhận được mã.
 */
public final class PayloadLimitInterceptor implements ServerInterceptor {
    private static final int MAX_BYTES = 1 << 20;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            private boolean refused;

            @Override
            public void onMessage(ReqT message) {
                if (message instanceof MessageLite proto && proto.getSerializedSize() > MAX_BYTES) {
                    refused = true;
                    AppException tooLarge = AppException.of(ErrorCode.PAYLOAD_TOO_LARGE);
                    call.close(GrpcErrors.status(tooLarge), GrpcErrors.trailers(tooLarge));
                    return;
                }
                super.onMessage(message);
            }

            @Override
            public void onHalfClose() {
                if (!refused) {
                    super.onHalfClose();
                }
            }
        };
    }
}

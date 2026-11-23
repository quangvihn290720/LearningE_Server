package com.learninge.server.common.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Lời gọi không có deadline (hoặc quá dài) vẫn bị cắt ở trần của nhóm RPC (TM-44).
 */
public final class DeadlineCapInterceptor implements ServerInterceptor {
    private final Function<String, Duration> capByMethod;
    private final ScheduledExecutorService scheduler;

    public DeadlineCapInterceptor(
            Function<String, Duration> capByMethod, ScheduledExecutorService scheduler) {
        this.capByMethod = capByMethod;
        this.scheduler = scheduler;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Duration cap = capByMethod.apply(call.getMethodDescriptor().getFullMethodName());
        Context.CancellableContext context =
                Context.current().withDeadlineAfter(cap.toMillis(), TimeUnit.MILLISECONDS, scheduler);
        ServerCall.Listener<ReqT> listener = Contexts.interceptCall(context, call, headers, next);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } finally {
                    context.cancel(null);
                }
            }

            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } finally {
                    context.cancel(null);
                }
            }
        };
    }
}

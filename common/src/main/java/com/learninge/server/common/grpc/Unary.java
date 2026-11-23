package com.learninge.server.common.grpc;

import io.grpc.stub.StreamObserver;

/**
 * Mọi RPC đều là unary: gửi một phản hồi rồi kết thúc.
 */
public final class Unary {
    private Unary() {
    }

    public static <T> void reply(StreamObserver<T> observer, T response) {
        observer.onNext(response);
        observer.onCompleted();
    }
}

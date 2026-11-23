package com.learninge.server.common.grpc;

import io.grpc.Context;

/**
 * Thông tin của một lời gọi, sống trong {@link Context} suốt thời gian xử lý.
 */
public final class CallInfo {
    public static final Context.Key<CallInfo> KEY = Context.key("learninge-call-info");

    private final String requestId;
    private final String rpc;
    private final long startNanos = System.nanoTime();
    private volatile String userHmac = "";
    private volatile int clientVersion;

    public CallInfo(String requestId, String rpc) {
        this.requestId = requestId;
        this.rpc = rpc;
    }

    public String requestId() {
        return requestId;
    }

    public String rpc() {
        return rpc;
    }

    public int durationMs() {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    public String userHmac() {
        return userHmac;
    }

    public void userHmac(String value) {
        userHmac = value;
    }

    public int clientVersion() {
        return clientVersion;
    }

    public void clientVersion(int value) {
        clientVersion = value;
    }
}

package com.learninge.server.edge.forward;

import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PullResponse;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.PushResponse;
import com.learninge.proto.app.v1.SyncServiceGrpc;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.proto.internal.v1.SyncBackendGrpc.SyncBackendBlockingStub;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.Unary;
import io.grpc.stub.StreamObserver;

import java.time.Duration;

/**
 * EP-11, EP-12.
 */
public final class SyncForwarder extends SyncServiceGrpc.SyncServiceImplBase {
    private final InternalChannels channels;

    public SyncForwarder(InternalChannels channels) {
        this.channels = channels;
    }

    private SyncBackendBlockingStub sync(Duration cap) {
        return Downstream.timed(SyncBackendGrpc.newBlockingStub(channels.to("sync")), cap);
    }

    @Override
    public void push(PushRequest request, StreamObserver<PushResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> sync(RpcDeadlines.PUSH).push(request)));
    }

    @Override
    public void pull(PullRequest request, StreamObserver<PullResponse> observer) {
        Unary.reply(observer, Downstream.call(() -> sync(RpcDeadlines.PULL).pull(request)));
    }
}

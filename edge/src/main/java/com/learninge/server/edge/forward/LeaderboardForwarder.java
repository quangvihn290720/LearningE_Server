package com.learninge.server.edge.forward;

import com.learninge.proto.app.v1.GetWeeklyRequest;
import com.learninge.proto.app.v1.GetWeeklyResponse;
import com.learninge.proto.app.v1.LeaderboardServiceGrpc;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.Unary;
import io.grpc.stub.StreamObserver;

/**
 * EP-13.
 */
public final class LeaderboardForwarder extends LeaderboardServiceGrpc.LeaderboardServiceImplBase {
    private final InternalChannels channels;

    public LeaderboardForwarder(InternalChannels channels) {
        this.channels = channels;
    }

    @Override
    public void getWeekly(GetWeeklyRequest request, StreamObserver<GetWeeklyResponse> observer) {
        Unary.reply(
                observer,
                Downstream.call(
                        () ->
                                Downstream.timed(
                                                GamificationBackendGrpc.newBlockingStub(channels.to("gamification")),
                                                RpcDeadlines.DEFAULT)
                                        .getWeekly(request)));
    }
}

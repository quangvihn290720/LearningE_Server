package com.learninge.server.gamification.grpc;

import com.learninge.proto.app.v1.GetWeeklyRequest;
import com.learninge.proto.app.v1.GetWeeklyResponse;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.proto.internal.v1.GetStatsRequest;
import com.learninge.proto.internal.v1.GetStatsResponse;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.gamification.service.LeaderboardService;
import io.grpc.stub.StreamObserver;

public final class GamificationBackendService
        extends GamificationBackendGrpc.GamificationBackendImplBase {
    private final LeaderboardService leaderboard;

    public GamificationBackendService(LeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @Override
    public void getWeekly(GetWeeklyRequest request, StreamObserver<GetWeeklyResponse> observer) {
        Unary.reply(observer, leaderboard.weekly(CallerContext.userId(), request.getWeek()));
    }

    @Override
    public void getStats(GetStatsRequest request, StreamObserver<GetStatsResponse> observer) {
        Unary.reply(observer, leaderboard.stats(CallerContext.userId()));
    }
}

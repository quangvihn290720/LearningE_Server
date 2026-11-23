package com.learninge.server.edge.forward;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.learninge.proto.app.v1.GetMeResponse;
import com.learninge.proto.app.v1.Stats;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.GamificationBackendGrpc;
import com.learninge.proto.internal.v1.GetProfileRequest;
import com.learninge.proto.internal.v1.GetProfileResponse;
import com.learninge.proto.internal.v1.GetStatsRequest;
import com.learninge.proto.internal.v1.GetStatsResponse;
import com.learninge.proto.internal.v1.GetStreakRequest;
import com.learninge.proto.internal.v1.GetStreakResponse;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.server.common.grpc.InternalChannels;
import io.grpc.Status;

import java.util.concurrent.ExecutionException;

/**
 * EP-05: hỏi 3 dịch vụ song song. Hồ sơ là bắt buộc; chuỗi ngày và XP tuần quá 300 ms hoặc lỗi thì
 * bỏ qua và báo {@code stats_partial}.
 */
public final class GetMeComposer {
    private final InternalChannels channels;

    public GetMeComposer(InternalChannels channels) {
        this.channels = channels;
    }

    GetMeResponse compose() {
        ListenableFuture<GetProfileResponse> profile =
                Downstream.timed(AuthBackendGrpc.newFutureStub(channels.to("auth")), RpcDeadlines.DEFAULT)
                        .getProfile(GetProfileRequest.getDefaultInstance());
        ListenableFuture<GetStreakResponse> streak =
                Downstream.timed(SyncBackendGrpc.newFutureStub(channels.to("sync")), RpcDeadlines.OPTIONAL)
                        .getStreak(GetStreakRequest.getDefaultInstance());
        ListenableFuture<GetStatsResponse> game =
                Downstream.timed(
                                GamificationBackendGrpc.newFutureStub(channels.to("gamification")),
                                RpcDeadlines.OPTIONAL)
                        .getStats(GetStatsRequest.getDefaultInstance());

        GetProfileResponse user = required(profile);
        GetStreakResponse streakOrNull = optional(streak);
        GetStatsResponse gameOrNull = optional(game);
        return GetMeResponse.newBuilder()
                .setUser(user.getUser())
                .setStats(stats(streakOrNull, gameOrNull))
                .setStatsPartial(streakOrNull == null || gameOrNull == null)
                .build();
    }

    /**
     * Tổng XP lấy từ sync (nguồn sự thật); chỉ khi sync ngừng mới dùng bản của gamification.
     */
    private static Stats stats(GetStreakResponse streak, GetStatsResponse game) {
        Stats.Builder stats = Stats.newBuilder();
        if (game != null) {
            stats.setTotalXp(game.getTotalXp()).setWeekXp(game.getWeekXp());
        }
        if (streak != null) {
            stats
                    .setTotalXp(streak.getTotalXp())
                    .setCurrentStreak(streak.getCurrentStreak())
                    .setLongestStreak(streak.getLongestStreak())
                    .setLastStudyDate(streak.getLastStudyDate());
        }
        return stats.build();
    }

    private static <T> T required(ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw Downstream.translate(
                    Status.fromThrowable(e.getCause()), Status.trailersFromThrowable(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.asRuntimeException();
        }
    }

    /**
     * Mỗi lời gọi tự có deadline 300 ms nên việc chờ luôn có giới hạn.
     */
    private static <T> T optional(ListenableFuture<T> future) {
        try {
            return Futures.successfulAsList(future).get().getFirst();
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

package com.learninge.server.sync.grpc;

import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PullResponse;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.PushResponse;
import com.learninge.proto.internal.v1.ExportUserStatsRequest;
import com.learninge.proto.internal.v1.ExportUserStatsResponse;
import com.learninge.proto.internal.v1.ExportXpEventsRequest;
import com.learninge.proto.internal.v1.ExportXpEventsResponse;
import com.learninge.proto.internal.v1.GetStreakRequest;
import com.learninge.proto.internal.v1.GetStreakResponse;
import com.learninge.proto.internal.v1.ProgressChanged;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.sync.service.PullService;
import com.learninge.server.sync.service.PushService;
import com.learninge.server.sync.store.SyncTables;
import com.learninge.server.sync.store.UserStatsStore;
import com.learninge.server.sync.store.UserStatsStore.UserStats;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Đổi message ↔ kiểu miền cho EP-11, EP-12 và các RPC nội bộ của sync. */
public final class SyncBackendService extends SyncBackendGrpc.SyncBackendImplBase {
  private static final int MAX_EXPORT = 1000;

  private final PushService push;
  private final PullService pull;
  private final SyncTables tables;
  private final UserStatsStore stats;
  private final Clock clock;

  public SyncBackendService(
      PushService push, PullService pull, SyncTables tables, UserStatsStore stats, Clock clock) {
    this.push = push;
    this.pull = pull;
    this.tables = tables;
    this.stats = stats;
    this.clock = clock;
  }

  @Override
  public void push(PushRequest request, StreamObserver<PushResponse> observer) {
    Unary.reply(observer, push.push(CallerContext.userId(), request));
  }

  @Override
  public void pull(PullRequest request, StreamObserver<PullResponse> observer) {
    Unary.reply(observer, pull.pull(CallerContext.userId(), request, clock.millis()));
  }

  @Override
  public void getStreak(GetStreakRequest request, StreamObserver<GetStreakResponse> observer) {
    UserStats found = stats.find(CallerContext.userId()).orElse(UserStats.NONE);
    LocalDate last = found.streak().lastStudyDate();
    Unary.reply(
        observer,
        GetStreakResponse.newBuilder()
            .setTotalXp(found.totalXp())
            .setCurrentStreak(found.streak().current())
            .setLongestStreak(found.streak().longest())
            .setLastStudyDate(last == null ? "" : last.toString())
            .build());
  }

  @Override
  public void exportXpEvents(
      ExportXpEventsRequest request, StreamObserver<ExportXpEventsResponse> observer) {
    int limit = Math.clamp(request.getLimit(), 1, MAX_EXPORT);
    List<XpRecorded> found =
        tables.xpEvents.exportAfter(tables.reader(), request.getAfterSeq(), limit + 1);
    boolean hasMore = found.size() > limit;
    List<XpRecorded> page = hasMore ? found.subList(0, limit) : found;
    Unary.reply(
        observer,
        ExportXpEventsResponse.newBuilder()
            .addAllEvents(page)
            .setNextAfterSeq(page.isEmpty() ? request.getAfterSeq() : page.getLast().getServerSeq())
            .setHasMore(hasMore)
            .build());
  }

  @Override
  public void exportUserStats(
      ExportUserStatsRequest request, StreamObserver<ExportUserStatsResponse> observer) {
    int limit = Math.clamp(request.getLimit(), 1, MAX_EXPORT);
    List<ProgressChanged> found = stats.exportAfter(request.getAfterUserId(), limit + 1);
    boolean hasMore = found.size() > limit;
    List<ProgressChanged> page = hasMore ? found.subList(0, limit) : found;
    Unary.reply(
        observer,
        ExportUserStatsResponse.newBuilder()
            .addAllStats(page)
            .setNextAfterUserId(
                page.isEmpty() ? request.getAfterUserId() : page.getLast().getUserId())
            .setHasMore(hasMore)
            .build());
  }
}

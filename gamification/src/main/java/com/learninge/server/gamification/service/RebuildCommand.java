package com.learninge.server.gamification.service;

import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.ExportProfilesRequest;
import com.learninge.proto.internal.v1.ExportProfilesResponse;
import com.learninge.proto.internal.v1.ExportUserStatsRequest;
import com.learninge.proto.internal.v1.ExportUserStatsResponse;
import com.learninge.proto.internal.v1.ExportXpEventsRequest;
import com.learninge.proto.internal.v1.ExportXpEventsResponse;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.common.ops.OpsCommand;
import com.mongodb.client.MongoDatabase;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=rebuild}: dựng lại toàn bộ kho từ nguồn sự thật (auth.db, sync.db). Chạy được khi
 * dịch vụ đang nhận sự kiện mới, vì mọi thao tác đều idempotent và chỉ tăng.
 */
public final class RebuildCommand implements OpsCommand {
    private static final Logger log = LoggerFactory.getLogger(RebuildCommand.class);
    private static final int PAGE = 1000;
    private static final long DEADLINE_SECONDS = 10;

    private final InternalChannels channels;
    private final GameUpdates updates;
    private final MongoSchema schema;
    private final MongoDatabase database;

    public RebuildCommand(
            InternalChannels channels, GameUpdates updates, MongoSchema schema, MongoDatabase database) {
        this.channels = channels;
        this.updates = updates;
        this.schema = schema;
        this.database = database;
    }

    @Override
    public String name() {
        return "rebuild";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        rebuild();
    }

    public void rebuild() {
        schema.apply(database);
        int profiles = loadProfiles();
        int streaks = loadStreaks();
        int events = loadXpEvents();
        log.info("Rebuild xong: {} hồ sơ, {} chuỗi ngày, {} sự kiện XP", profiles, streaks, events);
    }

    private int loadProfiles() {
        int count = 0;
        ExportProfilesResponse page = ExportProfilesResponse.newBuilder().setHasMore(true).build();
        while (page.getHasMore()) {
            page =
                    AuthBackendGrpc.newBlockingStub(channels.to("auth"))
                            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .exportProfiles(
                                    ExportProfilesRequest.newBuilder()
                                            .setAfterId(page.getNextAfterId())
                                            .setLimit(PAGE)
                                            .build());
            page.getProfilesList()
                    .forEach(
                            p -> updates.nicknameChanged(p.getUserId(), p.getNickname(), p.getProfileVersion()));
            count += page.getProfilesCount();
        }
        return count;
    }

    private int loadStreaks() {
        int count = 0;
        ExportUserStatsResponse page = ExportUserStatsResponse.newBuilder().setHasMore(true).build();
        while (page.getHasMore()) {
            page =
                    SyncBackendGrpc.newBlockingStub(channels.to("sync"))
                            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .exportUserStats(
                                    ExportUserStatsRequest.newBuilder()
                                            .setAfterUserId(page.getNextAfterUserId())
                                            .setLimit(PAGE)
                                            .build());
            page.getStatsList()
                    .forEach(
                            s ->
                                    updates.streakChanged(
                                            s.getUserId(), s.getCurrentStreak(), s.getLongestStreak(), s.getVersion()));
            count += page.getStatsCount();
        }
        return count;
    }

    private int loadXpEvents() {
        int count = 0;
        ExportXpEventsResponse page = ExportXpEventsResponse.newBuilder().setHasMore(true).build();
        while (page.getHasMore()) {
            page =
                    SyncBackendGrpc.newBlockingStub(channels.to("sync"))
                            .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                            .exportXpEvents(
                                    ExportXpEventsRequest.newBuilder()
                                            .setAfterSeq(page.getNextAfterSeq())
                                            .setLimit(PAGE)
                                            .build());
            page.getEventsList().forEach(e -> updates.xpRecorded(e, e.getXpUuid()));
            count += page.getEventsCount();
        }
        return count;
    }
}

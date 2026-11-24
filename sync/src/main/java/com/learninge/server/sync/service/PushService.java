package com.learninge.server.sync.service;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.ChangeResult;
import com.learninge.proto.app.v1.ChangeStatus;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.PushResponse;
import com.learninge.proto.app.v1.RecordError;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.id.Uuids;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.sync.domain.Streak;
import com.learninge.server.sync.service.Outcome.Applied;
import com.learninge.server.sync.service.Outcome.Duplicate;
import com.learninge.server.sync.service.Outcome.Rejected;
import com.learninge.server.sync.service.Outcome.Stale;
import com.learninge.server.sync.service.SyncEvents.BatchCounts;
import com.learninge.server.sync.store.SyncTables;
import com.learninge.server.sync.store.UserStatsStore;
import com.learninge.server.sync.store.UserStatsStore.UserStats;
import com.learninge.server.sync.store.XpEventTable.ScoredXp;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EP-11. Cả lô nằm trong <b>một</b> giao dịch: bản ghi, số thứ tự, thống kê và sự kiện outbox cùng
 * thành công hoặc cùng không có. Một bản ghi sai chỉ bị từ chối riêng nó.
 */
public final class PushService {
    private static final int MAX_CHANGES = 500;

    private final SqliteDatabase database;
    private final SyncTables tables;
    private final UserStatsStore statsStore;
    private final RecordWriter records;
    private final SyncEvents events;
    private final Clock clock;

    public PushService(
            SqliteDatabase database,
            SyncTables tables,
            UserStatsStore statsStore,
            RecordWriter records,
            SyncEvents events,
            Clock clock) {
        this.database = database;
        this.tables = tables;
        this.statsStore = statsStore;
        this.records = records;
        this.events = events;
        this.clock = clock;
    }

    public PushResponse push(String userId, PushRequest request) {
        if (request.getChangesCount() > MAX_CHANGES) {
            throw AppException.of(ErrorCode.BATCH_TOO_LARGE);
        }
        if (!Uuids.isCanonical(request.getDeviceId())) {
            throw AppException.invalid(
                    "device_id",
                    request.getDeviceId().isEmpty() ? FieldErrors.REQUIRED : FieldErrors.INVALID_FORMAT);
        }
        return database.inWriteTx(() -> pushInTx(userId, request.getChangesList()));
    }

    private PushResponse pushInTx(String userId, List<Change> changes) {
        if (tables.isPurged(userId)) {
            throw AppException.of(ErrorCode.ACCOUNT_DELETED);
        }
        long now = clock.millis();
        PushResponse.Builder response = PushResponse.newBuilder()
                .setServerTimeMs(now)
                .setDataEpoch(tables.epoch(tables.writer()));
        List<ScoredXp> xp = new ArrayList<>();
        Map<Change.DataCase, Integer> applied = new EnumMap<>(Change.DataCase.class);
        Map<LocalDate, Integer> xpByDay = new HashMap<>();
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            Outcome outcome = records.write(userId, change, now, xpByDay);
            if (outcome instanceof Applied a) {
                applied.merge(change.getDataCase(), 1, Integer::sum);
                if (a.xp() != null) {
                    xp.add(a.xp());
                }
            }
            response.addResults(toResult(i, change.getRecordId(), outcome));
        }
        if (!applied.isEmpty()) {
            finishBatch(userId, now, xp, applied);
        }
        return response.build();
    }

    private void finishBatch(
            String userId, long now, List<ScoredXp> xp, Map<Change.DataCase, Integer> applied) {
        LocalDate today = VnTime.date(now);
        UserStats before = statsStore.lock(userId);
        Streak streak =
                applied.containsKey(Change.DataCase.STUDY_LOG)
                        ? Streak.of(tables.studyLog.studyDays(tables.writer(), userId), today.plusDays(1))
                        : before.streak();
        long gained = xp.stream().mapToLong(ScoredXp::awarded).sum();
        // Phiên bản luôn tăng để bên nhận bỏ được sự kiện cũ đến muộn.
        UserStats after =
                new UserStats(before.totalXp() + gained, streak, Math.max(now, before.version() + 1));
        statsStore.save(userId, after);
        xp.forEach(x -> events.xpRecorded(userId, x));
        events.progressChanged(
                userId,
                after,
                today,
                new BatchCounts(
                        applied.getOrDefault(Change.DataCase.CARD_PROGRESS, 0),
                        applied.getOrDefault(Change.DataCase.QUIZ_ATTEMPT, 0),
                        applied.getOrDefault(Change.DataCase.EXAM_ATTEMPT, 0),
                        applied.values().stream().mapToInt(Integer::intValue).sum()));
    }

    private static ChangeResult toResult(int index, String recordId, Outcome outcome) {
        ChangeResult.Builder result = ChangeResult.newBuilder().setIndex(index).setRecordId(recordId);
        switch (outcome) {
            case Applied a -> {
                result.setStatus(ChangeStatus.CHANGE_STATUS_APPLIED).setServerSeq(a.seq());
                if (a.current() != null) {
                    result.setCurrent(a.current());
                }
            }
            case Stale s -> result
                    .setStatus(ChangeStatus.CHANGE_STATUS_STALE)
                    .setServerSeq(s.current().getServerSeq())
                    .setCurrent(s.current());
            case Duplicate d -> {
                result.setStatus(ChangeStatus.CHANGE_STATUS_DUPLICATE).setServerSeq(d.seq());
                if (d.current() != null) {
                    result.setCurrent(d.current());
                }
            }
            case Rejected r -> result
                    .setStatus(ChangeStatus.CHANGE_STATUS_REJECTED)
                    .setError(
                            RecordError.newBuilder()
                                    .setCode(r.reason().name())
                                    .setMessage(r.reason().message()));
        }
        return result.build();
    }
}

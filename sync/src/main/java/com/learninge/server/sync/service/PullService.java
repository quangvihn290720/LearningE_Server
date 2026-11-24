package com.learninge.server.sync.service;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.PullRequest;
import com.learninge.proto.app.v1.PullResponse;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.sync.store.SyncTables;

import java.util.List;

/**
 * EP-12: kéo thay đổi theo mốc {@code server_seq}, cùng một ảnh chụp dữ liệu.
 */
public final class PullService {
    private static final int MAX_PAGE = 500;

    private record Snapshot(List<Change> changes, long epoch) {
    }

    private final SqliteDatabase database;
    private final SyncTables tables;

    public PullService(SqliteDatabase database, SyncTables tables) {
        this.database = database;
        this.tables = tables;
    }

    public PullResponse pull(String userId, PullRequest request, long now) {
        FieldErrors errors = new FieldErrors();
        errors.check(request.getSince() >= 0, "since", FieldErrors.OUT_OF_RANGE);
        errors.check(
                request.getLimit() >= 0 && request.getLimit() <= MAX_PAGE,
                "limit",
                FieldErrors.OUT_OF_RANGE);
        errors.throwIfAny();
        int limit = request.getLimit() == 0 ? MAX_PAGE : request.getLimit();

        // Lấy dư một bản ghi để biết còn trang sau hay không.
        Snapshot snapshot =
                database.inReadTx(
                        () ->
                                new Snapshot(
                                        tables.changesAfter(userId, request.getSince(), limit + 1),
                                        tables.epoch(tables.reader())));
        List<Change> found = snapshot.changes();
        boolean hasMore = found.size() > limit;
        List<Change> page = hasMore ? found.subList(0, limit) : found;
        return PullResponse.newBuilder()
                .addAllChanges(page)
                .setNextCursor(page.isEmpty() ? request.getSince() : page.getLast().getServerSeq())
                .setHasMore(hasMore)
                .setServerTimeMs(now)
                .setDataEpoch(snapshot.epoch())
                .build();
    }
}

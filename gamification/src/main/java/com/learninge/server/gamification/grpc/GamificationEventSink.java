package com.learninge.server.gamification.grpc;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventSinkBase;
import com.learninge.server.gamification.service.GameUpdates;
import com.learninge.server.gamification.store.XpEventStore.XpEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Nhận sự kiện từ auth và sync.
 */
public final class GamificationEventSink extends EventSinkBase {
    private static final int CHUNK = 50;

    private final GameUpdates updates;

    public GamificationEventSink(GameUpdates updates) {
        super(Destination.GAMIFICATION, CHUNK);
        this.updates = updates;
    }

    /**
     * XP gom lại để tính điểm một lần cho mỗi người, không phải một lần cho mỗi sự kiện.
     */
    @Override
    protected void handleAll(List<Event> events) {
        List<XpEvent> xp = new ArrayList<>();
        for (Event event : events) {
            if (event.hasXpRecorded()) {
                xp.add(new XpEvent(event.getXpRecorded(), event.getEventId()));
            } else {
                handle(event);
            }
        }
        if (!xp.isEmpty()) {
            updates.xpRecorded(xp);
        }
    }

    @Override
    protected void handle(Event event) {
        switch (event.getPayloadCase()) {
            case USER_REGISTERED -> {
                var p = event.getUserRegistered();
                updates.nicknameChanged(p.getUserId(), p.getNickname(), p.getCreatedAtMs());
            }
            case USER_PROFILE_CHANGED -> {
                var p = event.getUserProfileChanged();
                updates.nicknameChanged(p.getUserId(), p.getNickname(), p.getVersion());
            }
            case USER_DELETED -> updates.userDeleted(event.getUserDeleted().getUserId());
            case PROGRESS_CHANGED -> {
                var p = event.getProgressChanged();
                updates.streakChanged(
                        p.getUserId(), p.getCurrentStreak(), p.getLongestStreak(), p.getVersion());
            }
            default -> throw new IllegalStateException("EventPolicy đã chặn " + event.getType());
        }
    }
}

package com.learninge.server.gamification.grpc;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventSinkBase;
import com.learninge.server.gamification.service.GameUpdates;

/** Nhận sự kiện từ auth và sync. */
public final class GamificationEventSink extends EventSinkBase {
  private final GameUpdates updates;

  public GamificationEventSink(GameUpdates updates) {
    super(Destination.GAMIFICATION);
    this.updates = updates;
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
      case XP_RECORDED -> updates.xpRecorded(event.getXpRecorded(), event.getEventId());
      case PROGRESS_CHANGED -> {
        var p = event.getProgressChanged();
        updates.streakChanged(
            p.getUserId(), p.getCurrentStreak(), p.getLongestStreak(), p.getVersion());
      }
      default -> throw new IllegalStateException("EventPolicy đã chặn " + event.getType());
    }
  }
}

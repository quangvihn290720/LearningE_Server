package com.learninge.server.gamification.service;

import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.gamification.domain.WeeklyTotal;
import com.learninge.server.gamification.store.PlayerStore;
import com.learninge.server.gamification.store.WeeklyXpStore;
import com.learninge.server.gamification.store.XpEventStore;
import java.time.Clock;

/**
 * Áp sự kiện vào kho. Mọi thao tác idempotent; chạy lần lượt để việc kiểm "đã xóa" và ghi không bị
 * xen vào bởi sự kiện xóa tài khoản từ nguồn khác.
 */
public final class GameUpdates {
  private final XpEventStore xpEvents;
  private final WeeklyXpStore weekly;
  private final PlayerStore players;
  private final Clock clock;

  public GameUpdates(
      XpEventStore xpEvents, WeeklyXpStore weekly, PlayerStore players, Clock clock) {
    this.xpEvents = xpEvents;
    this.weekly = weekly;
    this.players = players;
    this.clock = clock;
  }

  public synchronized void xpRecorded(XpRecorded xp, String eventId) {
    if (players.isDeleted(xp.getUserId())) {
      return;
    }
    xpEvents.insertIfAbsent(xp, eventId);
    recompute(xp.getUserId(), xp.getWeekStart());
  }

  public synchronized void nicknameChanged(String userId, String nickname, long version) {
    if (!players.isDeleted(userId)) {
      players.saveNickname(userId, nickname, version, clock.instant());
    }
  }

  public synchronized void streakChanged(String userId, int current, int longest, long version) {
    if (!players.isDeleted(userId)) {
      players.saveStreak(userId, current, longest, version, clock.instant());
    }
  }

  public synchronized void userDeleted(String userId) {
    players.markDeleted(userId, clock.instant());
    xpEvents.deleteUser(userId);
    weekly.deleteUser(userId);
    players.deleteUser(userId);
  }

  private void recompute(String userId, String weekStart) {
    WeeklyTotal total = WeeklyTotal.of(xpEvents.week(userId, weekStart));
    weekly.raise(userId, weekStart, total, clock.instant());
    players.raiseTotalXp(userId, xpEvents.totalXp(userId), clock.instant());
  }
}

package com.learninge.server.gamification.service;

import com.learninge.server.gamification.domain.WeeklyTotal;
import com.learninge.server.gamification.store.PlayerStore;
import com.learninge.server.gamification.store.WeeklyXpStore;
import com.learninge.server.gamification.store.XpEventStore;
import com.learninge.server.gamification.store.XpEventStore.XpEvent;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Ghi cả cụm rồi tính lại đúng một lần cho mỗi người và mỗi tuần có mặt trong cụm.
     */
    public synchronized void xpRecorded(List<XpEvent> events) {
        Set<String> deleted =
                players.deletedAmong(events.stream().map(e -> e.xp().getUserId()).collect(Collectors.toSet()));
        List<XpEvent> live =
                events.stream().filter(e -> !deleted.contains(e.xp().getUserId())).toList();
        if (live.isEmpty()) {
            return;
        }
        xpEvents.insertIfAbsent(live);
        Map<String, Set<String>> weeksOfUser = new HashMap<>();
        live.forEach(
                e ->
                        weeksOfUser
                                .computeIfAbsent(e.xp().getUserId(), u -> new HashSet<>())
                                .add(e.xp().getWeekStart()));
        weeksOfUser.forEach(this::recompute);
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

    private void recompute(String userId, Set<String> weeks) {
        for (String week : weeks) {
            WeeklyTotal total = WeeklyTotal.of(xpEvents.week(userId, week));
            weekly.raise(userId, week, total, clock.instant());
        }
        players.raiseTotalXp(userId, xpEvents.totalXp(userId), clock.instant());
    }
}

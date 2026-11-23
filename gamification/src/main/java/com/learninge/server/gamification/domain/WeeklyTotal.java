package com.learninge.server.gamification.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tổng XP tuần tính lại từ sự kiện gốc, trần 500 XP/ngày. Không cộng dồn nên nhận trùng sự kiện
 * không sai. Hàm thuần.
 *
 * @param reachedAt lúc server ghi nhận sự kiện cuối cùng làm tăng XP, dùng phân hạng khi hòa
 *                  (BR-069)
 */
public record WeeklyTotal(int xp, Instant reachedAt) {
    private static final int DAILY_CAP = 500;

    /**
     * Một sự kiện XP của một người trong một tuần.
     */
    public record Entry(String localDate, int xpAwarded, Instant receivedAt) {
    }

    public static WeeklyTotal of(List<Entry> entries) {
        Map<String, Integer> perDay = new HashMap<>();
        Instant reachedAt = Instant.EPOCH;
        for (Entry entry : entries) {
            perDay.merge(entry.localDate(), entry.xpAwarded(), Integer::sum);
            if (entry.xpAwarded() > 0 && entry.receivedAt().isAfter(reachedAt)) {
                reachedAt = entry.receivedAt();
            }
        }
        int total = perDay.values().stream().mapToInt(day -> Math.min(day, DAILY_CAP)).sum();
        return new WeeklyTotal(total, reachedAt);
    }
}

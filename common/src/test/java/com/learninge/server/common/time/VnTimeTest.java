package com.learninge.server.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class VnTimeTest {
    @Test
    void weekChangesAtMondayMidnightVietnamTime() {
        // BR-069: 23:59 Chủ nhật 15/11 giờ VN còn tuần cũ; 17:30 UTC = 00:30 thứ Hai 16/11 là tuần mới.
        assertThat(week("2026-11-15T16:59:59Z")).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(week("2026-11-15T17:00:00Z")).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(week("2026-11-15T17:30:00Z")).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    private static LocalDate week(String instant) {
        return VnTime.weekStart(VnTime.date(Instant.parse(instant).toEpochMilli()));
    }
}

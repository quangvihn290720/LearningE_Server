package com.learninge.server.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StreakTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

  private static Streak of(String... days) {
    return Streak.of(Arrays.stream(days).map(LocalDate::parse).toList(), TODAY.plusDays(1));
  }

  @Test
  void countsConsecutiveDays() {
    assertThat(of()).isEqualTo(Streak.NONE);
    // BR-031: học 8/11, 9/11, nghỉ 10/11, học 11/11 → chuỗi 1, dài nhất 2.
    assertThat(of("2026-11-08", "2026-11-09", "2026-11-11")).isEqualTo(new Streak(1, 2, TODAY));
    assertThat(of("2026-10-31", "2026-11-01")).extracting(Streak::current).isEqualTo(2);
  }

  @Test
  void ignoresDaysFromAWrongDeviceClock() {
    assertThat(of("2026-11-11", "2030-01-01")).isEqualTo(new Streak(1, 1, TODAY));
  }
}

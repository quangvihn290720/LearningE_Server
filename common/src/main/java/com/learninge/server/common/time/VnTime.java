package com.learninge.server.common.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** Ngày và tuần ISO theo giờ Việt Nam, dùng cho XP và bảng xếp hạng (BR-068, BR-069). */
public final class VnTime {
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private VnTime() {}

  public static LocalDate date(long epochMillis) {
    return Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate();
  }

  /** Thứ Hai của tuần chứa {@code date}. */
  public static LocalDate weekStart(LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}

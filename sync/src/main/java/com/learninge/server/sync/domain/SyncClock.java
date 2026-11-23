package com.learninge.server.sync.domain;

import java.time.Duration;

/** Kẹp {@code updated_at} để đồng hồ máy chạy nhanh không thắng mãi mãi (TM-17). */
public final class SyncClock {
  private static final long TOLERANCE_MILLIS = Duration.ofMinutes(5).toMillis();

  private SyncClock() {}

  public static long clamp(long updatedAt, long now) {
    return updatedAt > now + TOLERANCE_MILLIS ? now : updatedAt;
  }
}

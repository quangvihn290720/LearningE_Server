package com.learninge.server.auth.domain;

import java.time.Duration;

/**
 * Khóa tăng dần (BR-003, BR-008), dùng chung cho đăng nhập và đặt lại mật khẩu: sai lần thứ 5 khóa
 * 60 giây, mỗi lần khóa sau gấp đôi, trần 1 giờ; 24 giờ không sai thì về 0. Bất biến, không I/O.
 */
public record LockState(int failedCount, int lockCount, long lockedUntil, long lastFailAt) {
  public static final LockState CLEAR = new LockState(0, 0, 0, 0);

  private static final int MAX_FAILURES = 5;
  private static final long BASE_LOCK_MILLIS = Duration.ofSeconds(60).toMillis();
  private static final long MAX_LOCK_MILLIS = Duration.ofHours(1).toMillis();
  private static final long FORGET_AFTER_MILLIS = Duration.ofHours(24).toMillis();

  /** Trạng thái hiệu lực tại {@code now}: quá 24 giờ không sai thì coi như chưa từng sai. */
  public LockState at(long now) {
    return now > Math.max(lockedUntil, lastFailAt) + FORGET_AFTER_MILLIS ? CLEAR : this;
  }

  public boolean isLocked(long now) {
    return now < lockedUntil;
  }

  public long secondsLeft(long now) {
    return Math.max(0, Math.ceilDiv(lockedUntil - now, 1000));
  }

  /** Ghi nhận một lần sai. Chỉ gọi khi không đang khóa. */
  public LockState afterFailure(long now) {
    LockState current = at(now);
    int failures = current.failedCount + 1;
    if (failures < MAX_FAILURES) {
      return new LockState(failures, current.lockCount, 0, now);
    }
    int level = current.lockCount + 1;
    // Dịch tối đa 6 bit (64 phút) là đã vượt trần; chặn số mũ để không tràn.
    long duration = Math.min(BASE_LOCK_MILLIS << Math.min(level - 1, 6), MAX_LOCK_MILLIS);
    return new LockState(0, level, now + duration, now);
  }
}

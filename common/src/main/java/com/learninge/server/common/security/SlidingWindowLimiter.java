package com.learninge.server.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Đếm sự kiện theo khóa trong cửa sổ trượt, giữ trong bộ nhớ có trần. Dùng cho giới hạn tần suất ở
 * edge và đếm sai mật khẩu hiện tại ở auth.
 */
public final class SlidingWindowLimiter {
  private static final int MAX_KEYS = 50_000;

  private final int limit;
  private final long windowMillis;
  private final Clock clock;
  private final Cache<String, Deque<Long>> hits;

  public SlidingWindowLimiter(int limit, Duration window, Clock clock) {
    this.limit = limit;
    this.windowMillis = window.toMillis();
    this.clock = clock;
    this.hits =
        Caffeine.newBuilder()
            .maximumSize(MAX_KEYS)
            .expireAfterWrite(window)
            .ticker(() -> clock.millis() * 1_000_000)
            .build();
  }

  /** Còn chỗ thì ghi nhận và trả 0; hết chỗ thì trả số giây phải chờ, không ghi nhận. */
  public long tryAcquire(String key) {
    long[] retryAfter = new long[1];
    hits.asMap()
        .compute(
            key,
            (k, existing) -> {
              Deque<Long> window = existing == null ? new ArrayDeque<>() : existing;
              retryAfter[0] = secondsUntilFree(window);
              if (retryAfter[0] == 0) {
                window.addLast(clock.millis());
              }
              return window;
            });
    return retryAfter[0];
  }

  /** Chỉ hỏi, không ghi nhận. */
  public long retryAfterSeconds(String key) {
    long[] retryAfter = new long[1];
    hits.asMap()
        .computeIfPresent(
            key,
            (k, window) -> {
              retryAfter[0] = secondsUntilFree(window);
              return window;
            });
    return retryAfter[0];
  }

  public void reset(String key) {
    hits.invalidate(key);
  }

  private long secondsUntilFree(Deque<Long> window) {
    long now = clock.millis();
    while (!window.isEmpty() && window.peekFirst() <= now - windowMillis) {
      window.removeFirst();
    }
    if (window.size() < limit) {
      return 0;
    }
    long waitMillis = window.peekFirst() + windowMillis - now;
    return Math.max(1, Math.ceilDiv(waitMillis, 1000));
  }
}

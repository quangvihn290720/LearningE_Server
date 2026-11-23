package com.learninge.server.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.server.common.testing.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {
  private final MutableClock clock = MutableClock.at("2026-11-23T00:00:00Z");
  private final SlidingWindowLimiter limiter =
      new SlidingWindowLimiter(2, Duration.ofSeconds(60), clock);

  @Test
  void blocksAfterLimitAndReportsWait() {
    assertThat(limiter.tryAcquire("a")).isZero();
    clock.advance(Duration.ofSeconds(10));
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isEqualTo(50);
    assertThat(limiter.tryAcquire("b")).isZero();
  }

  @Test
  void windowSlidesInsteadOfResettingAllAtOnce() {
    limiter.tryAcquire("a");
    clock.advance(Duration.ofSeconds(30));
    limiter.tryAcquire("a");
    clock.advance(Duration.ofSeconds(30));
    assertThat(limiter.tryAcquire("a")).isZero();
    assertThat(limiter.tryAcquire("a")).isEqualTo(30);
  }

  @Test
  void rejectedAttemptsDoNotExtendTheWait() {
    limiter.tryAcquire("a");
    limiter.tryAcquire("a");
    for (int i = 0; i < 100; i++) {
      limiter.tryAcquire("a");
    }
    clock.advance(Duration.ofSeconds(60));
    assertThat(limiter.tryAcquire("a")).isZero();
  }
}

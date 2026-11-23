package com.learninge.server.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LockStateTest {
  @Test
  void locksDoubleFromOneMinuteToOneHour() {
    long now = 1_000_000_000_000L;
    LockState state = LockState.CLEAR;
    List<Long> locks = new ArrayList<>();
    for (int level = 0; level < 9; level++) {
      for (int i = 1; i <= 4; i++) {
        state = state.afterFailure(now);
        assertThat(state.isLocked(now)).isFalse();
      }
      state = state.afterFailure(now);
      assertThat(state.isLocked(now)).isTrue();
      locks.add(state.secondsLeft(now));
      now += state.secondsLeft(now) * 1000;
      assertThat(state.isLocked(now)).isFalse();
    }
    assertThat(locks).containsExactly(60L, 120L, 240L, 480L, 960L, 1920L, 3600L, 3600L, 3600L);
  }

  @Test
  void forgetsEverythingAfterADayWithoutFailures() {
    LockState state = LockState.CLEAR;
    for (int i = 0; i < 5; i++) {
      state = state.afterFailure(0);
    }
    long edge = state.lockedUntil() + Duration.ofHours(24).toMillis();
    assertThat(state.at(edge)).isEqualTo(state);
    assertThat(state.at(edge + 1)).isEqualTo(LockState.CLEAR);
  }

  @Test
  void roundsRemainingSecondsUp() {
    assertThat(new LockState(0, 1, 1001, 0).secondsLeft(0)).isEqualTo(2);
  }
}

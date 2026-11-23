package com.learninge.server.common.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Đồng hồ giả: test chỉnh giờ thay vì chờ thật. */
public final class MutableClock extends Clock {
  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public static MutableClock at(String instant) {
    return new MutableClock(Instant.parse(instant));
  }

  public void advance(Duration duration) {
    now = now.plus(duration);
  }

  public void set(String instant) {
    now = Instant.parse(instant);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException();
  }
}

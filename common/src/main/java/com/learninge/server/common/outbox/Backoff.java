package com.learninge.server.common.outbox;

import java.util.function.DoubleSupplier;

/** Lùi dần 2, 4, 8, 15, 15… giây, nhiễu ±20% (DATABASE-DESIGN §B3). */
final class Backoff {
  private final long maxMillis;
  private final DoubleSupplier random;

  Backoff(long maxMillis, DoubleSupplier random) {
    this.maxMillis = maxMillis;
    this.random = random;
  }

  /** {@code attempts} tính cả lần vừa lỗi (từ 1). */
  long delayMillis(int attempts) {
    long base = Math.min(1000L << Math.min(attempts, 20), maxMillis);
    return Math.round(base * (0.8 + 0.4 * random.getAsDouble()));
  }
}

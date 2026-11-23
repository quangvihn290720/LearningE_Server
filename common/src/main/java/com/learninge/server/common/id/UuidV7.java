package com.learninge.server.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUIDv7 (RFC 9562 §5.7): 48 bit mili giây + ngẫu nhiên, dùng làm {@code event_id} của outbox. */
public final class UuidV7 {
  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID next(Clock clock) {
    return of(clock.millis(), RANDOM.nextLong(), RANDOM.nextLong());
  }

  static UUID of(long epochMillis, long randomA, long randomB) {
    long msb = (epochMillis << 16) | 0x7000L | (randomA & 0x0fffL);
    long lsb = 0x8000_0000_0000_0000L | (randomB & 0x3fff_ffff_ffff_ffffL);
    return new UUID(msb, lsb);
  }
}

package com.learninge.server.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.server.common.testing.MutableClock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidTest {
  @Test
  void v5MatchesRfc9562Vector() {
    UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    assertThat(UuidV5.of(dns, "www.example.com"))
        .hasToString("2ed6657d-e927-568b-95e1-2665a8aea6a2");
  }

  @Test
  void v7MatchesRfc9562Vector() {
    assertThat(UuidV7.of(0x017F22E279B0L, 0xCC3L, 0x18C4DC0C0C07398FL))
        .hasToString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f");
  }

  @Test
  void v7SortsByTime() {
    MutableClock clock = MutableClock.at("2026-11-23T00:00:00Z");
    String first = UuidV7.next(clock).toString();
    clock.advance(java.time.Duration.ofMillis(1));
    assertThat(UuidV7.next(clock).toString()).isGreaterThan(first);
  }

  /** Cặp cố định dùng chung với test của app (SyncIds). */
  @Test
  void notebookIsDeterministic() {
    String user = "11111111-2222-4333-8444-555555555555";
    assertThat(UuidV5.notebook(user))
        .isEqualTo(UuidV5.notebook(user))
        .isNotEqualTo(UuidV5.notebook("21111111-2222-4333-8444-555555555555"));
    assertThat(UUID.fromString(UuidV5.notebook(user)).version()).isEqualTo(5);
  }

  @Test
  void canonicalRejectsUppercaseAndJunk() {
    assertThat(Uuids.isCanonical("11111111-2222-4333-8444-555555555555")).isTrue();
    assertThat(Uuids.isCanonical("11111111-2222-4333-8444-55555555555A")).isFalse();
    assertThat(Uuids.isCanonical("x\r\ny")).isFalse();
  }
}

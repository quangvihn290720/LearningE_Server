package com.learninge.server.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretKeysTest {
  @Test
  void prodRefusesMissingOrShortKeyWithoutLeakingIt() {
    assertThatThrownBy(() -> SecretKeys.hmacKey("K", Secret.valueOf(""), AppEnv.PROD))
        .isInstanceOf(IllegalStateException.class);
    String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
    assertThatThrownBy(() -> SecretKeys.hmacKey("K", Secret.valueOf(shortKey), AppEnv.PROD))
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> SecretKeys.hmacKey("K", Secret.valueOf("!secret!"), AppEnv.DEV))
        .hasMessageNotContaining("!secret!")
        .hasNoCause();
  }

  @Test
  void devGetsTemporaryKey() {
    assertThat(SecretKeys.hmacKey("K", Secret.valueOf(""), AppEnv.DEV)).hasSize(32);
  }

  @Test
  void secretNeverPrintsItsValue() {
    assertThat(Secret.valueOf("abc")).hasToString("***");
  }

  @Test
  void internalTokenIsAlwaysRequired() {
    assertThatThrownBy(() -> SecretKeys.internalToken(Secret.valueOf("short")))
        .isInstanceOf(IllegalStateException.class);
  }
}

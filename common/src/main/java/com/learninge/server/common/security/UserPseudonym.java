package com.learninge.server.common.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Bút danh {@code user_hmac} cho log và insight: 32 ký tự hex đầu của HMAC-SHA256(khóa bút danh,
 * user). Chỉ auth và sync giữ khóa (D-10).
 */
public final class UserPseudonym {
  private final byte[] key;

  public UserPseudonym(byte[] key) {
    this.key = key.clone();
  }

  public String of(String userId) {
    byte[] mac = Digests.hmacSha256(key, userId.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(mac, 0, 16);
  }
}

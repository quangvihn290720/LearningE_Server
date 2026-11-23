package com.learninge.server.auth.domain;

import com.learninge.server.common.security.Digests;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token phiên 256 bit (BR-057). Server chỉ lưu SHA-256; chuỗi rõ chỉ trả cho app một lần. */
public record OpaqueToken(String value) {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int BYTES = 32;

  public static OpaqueToken generate() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    return new OpaqueToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  public String hash() {
    return hashHex(Digests.sha256(value.getBytes(StandardCharsets.US_ASCII)));
  }

  public static String hashHex(byte[] sha256) {
    return HexFormat.of().formatHex(sha256);
  }

  @Override
  public String toString() {
    return "***";
  }
}

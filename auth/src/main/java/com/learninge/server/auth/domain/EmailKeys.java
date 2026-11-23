package com.learninge.server.auth.domain;

import com.learninge.server.common.security.Digests;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Giá trị suy từ email bằng HMAC với khóa chỉ auth giữ, để người ngoài không tự tính được email nào
 * có thật (BR-076, TM-03).
 */
public final class EmailKeys {
  private static final int QUESTIONS = 5;

  private final byte[] key;

  public EmailKeys(byte[] key) {
    this.key = key.clone();
  }

  /** Câu hỏi giả ổn định (1..5) cho email không tồn tại. */
  public int fakeQuestionId(String email) {
    long value = ByteBuffer.wrap(hmac(email)).getLong();
    return (int) Long.remainderUnsigned(value, QUESTIONS) + 1;
  }

  /** Khóa dòng {@code auth_attempts}; tiền tố tách khỏi {@link #fakeQuestionId}. */
  public String attemptSubject(String email) {
    return HexFormat.of().formatHex(hmac("attempt:" + email));
  }

  /** Dấu vân tay khóa: đổi khóa thì các {@code attemptSubject} cũ không còn khớp. */
  public String fingerprint() {
    return HexFormat.of().formatHex(hmac("fingerprint"), 0, 8);
  }

  private byte[] hmac(String value) {
    return Digests.hmacSha256(key, value.getBytes(StandardCharsets.UTF_8));
  }
}

package com.learninge.server.auth.domain;

import com.learninge.server.common.error.AppException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256, 120.000 vòng, salt 16 byte (DEC-003). Tối đa N phép băm cùng lúc; chờ quá 2
 * giây thì báo bận để kẻ tấn công không làm cạn CPU (TM-05).
 */
public final class PasswordHasher {
  public record Hash(String hash, String salt) {}

  private static final int ITERATIONS = 120_000;
  private static final int KEY_BITS = 256;
  private static final int SALT_BYTES = 16;
  private static final long MAX_WAIT_SECONDS = 2;

  private final SecureRandom random = new SecureRandom();
  private final Semaphore permits;

  /** Hash giả cho email không tồn tại: thời gian xử lý như thật (BR-076). */
  private final Hash decoy;

  public PasswordHasher(int maxConcurrent) {
    this.permits = new Semaphore(maxConcurrent);
    this.decoy = hash("decoy-password-0");
  }

  public Hash hash(String secret) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] derived = deriveLimited(secret, salt);
    return new Hash(encode(derived), encode(salt));
  }

  public boolean verify(String secret, Hash expected) {
    byte[] derived = deriveLimited(secret, Base64.getDecoder().decode(expected.salt()));
    return MessageDigest.isEqual(derived, Base64.getDecoder().decode(expected.hash()));
  }

  public Hash decoy() {
    return decoy;
  }

  private byte[] deriveLimited(String secret, byte[] salt) {
    try {
      if (!permits.tryAcquire(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw AppException.busy();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw AppException.busy();
    }
    try {
      return pbkdf2(secret.toCharArray(), salt, ITERATIONS, KEY_BITS);
    } finally {
      permits.release();
    }
  }

  static byte[] pbkdf2(char[] secret, byte[] salt, int iterations, int keyBits) {
    PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, keyBits);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}

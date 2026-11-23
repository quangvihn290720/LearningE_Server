package com.learninge.server.common.config;

import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Đọc và kiểm khóa HMAC: base64, giải mã ra ít nhất 32 byte. */
public final class SecretKeys {
  private static final Logger log = LoggerFactory.getLogger(SecretKeys.class);
  private static final int MIN_BYTES = 32;

  private SecretKeys() {}

  /** Thiếu khóa: prod từ chối khởi động, dev sinh khóa tạm (mất khi khởi động lại). */
  public static byte[] hmacKey(String name, Secret secret, AppEnv env) {
    if (secret.isBlank()) {
      if (env == AppEnv.PROD) {
        throw new IllegalStateException("Thiếu " + name + " khi APP_ENV=prod");
      }
      log.warn("{} chưa đặt: dùng khóa tạm chỉ cho dev", name);
      byte[] temporary = new byte[MIN_BYTES];
      new SecureRandom().nextBytes(temporary);
      return temporary;
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(secret.reveal());
    } catch (IllegalArgumentException e) {
      // Không kèm nguyên nhân: thông điệp của JDK có thể chứa ký tự của khóa.
      throw new IllegalStateException(name + " không phải base64 hợp lệ");
    }
    if (key.length < MIN_BYTES) {
      throw new IllegalStateException(name + " phải có ít nhất 32 byte");
    }
    return key;
  }

  /** Khóa nội bộ bắt buộc ở mọi môi trường: thiếu thì các dịch vụ không gọi được nhau. */
  public static Secret internalToken(Secret secret) {
    if (secret.reveal().length() < MIN_BYTES) {
      throw new IllegalStateException("LEARNINGE_INTERNAL_TOKEN phải có ít nhất 32 ký tự");
    }
    return secret;
  }
}

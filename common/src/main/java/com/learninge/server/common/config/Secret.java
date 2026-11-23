package com.learninge.server.common.config;

/** Bí mật không bao giờ lọt vào log hay {@code toString()} của cấu hình (TM-56). */
public final class Secret {
  private final String value;

  private Secret(String value) {
    this.value = value;
  }

  /** Tên {@code valueOf} để Spring tự đổi chuỗi cấu hình sang {@code Secret}. */
  public static Secret valueOf(String value) {
    return new Secret(value);
  }

  public String reveal() {
    return value;
  }

  public boolean isBlank() {
    return value.isBlank();
  }

  @Override
  public String toString() {
    return "***";
  }
}

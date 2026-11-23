package com.learninge.server.common.outbox;

/** Dịch vụ nhận sự kiện. {@link #id()} trùng tên kênh và giá trị cột {@code outbox.destination}. */
public enum Destination {
  SYNC,
  GAMIFICATION,
  INSIGHT;

  public String id() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

package com.learninge.server.common.outbox;

import static com.learninge.server.common.outbox.Destination.GAMIFICATION;
import static com.learninge.server.common.outbox.Destination.INSIGHT;
import static com.learninge.server.common.outbox.Destination.SYNC;

import com.learninge.proto.internal.v1.Event;
import java.util.List;
import java.util.Optional;

/** Danh mục sự kiện: ai phát, ai nhận (DATABASE-DESIGN §B3). Bảng cố định này cũng là luật kiểm. */
public enum EventType {
  USER_REGISTERED("user.registered", "user", "auth", SYNC, GAMIFICATION, INSIGHT),
  USER_PROFILE_CHANGED("user.profile_changed", "user", "auth", GAMIFICATION, INSIGHT),
  USER_DELETED("user.deleted", "user", "auth", SYNC, GAMIFICATION, INSIGHT),
  AUTH_LOGIN_FAILED("auth.login_failed", "auth", "auth", INSIGHT),
  AUTH_LOCKED("auth.locked", "auth", "auth", INSIGHT),
  XP_RECORDED("xp.recorded", "xp_event", "sync", GAMIFICATION, INSIGHT),
  PROGRESS_CHANGED("progress.changed", "user", "sync", GAMIFICATION, INSIGHT);

  private final String id;
  private final String aggregate;
  private final String source;
  private final List<Destination> destinations;

  EventType(String id, String aggregate, String source, Destination... destinations) {
    this.id = id;
    this.aggregate = aggregate;
    this.source = source;
    this.destinations = List.of(destinations);
  }

  public static Optional<EventType> byId(String id) {
    for (EventType type : values()) {
      if (type.id.equals(id)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  public String id() {
    return id;
  }

  public String aggregate() {
    return aggregate;
  }

  public String source() {
    return source;
  }

  public List<Destination> destinations() {
    return destinations;
  }

  /** Tên enum và nhánh {@code oneof payload} trùng nhau, vd {@code USER_DELETED}. */
  public boolean matches(Event.PayloadCase payload) {
    return payload.name().equals(name());
  }
}

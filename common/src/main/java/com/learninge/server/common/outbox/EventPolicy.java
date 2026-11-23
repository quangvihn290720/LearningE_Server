package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.common.id.Uuids;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bên nhận kiểm sự kiện trước khi xử lý (D-13): đúng nguồn, đúng đích, payload đúng ràng buộc.
 *
 * <p>Khóa nội bộ là lớp chặn chính; đây là lớp phòng thủ thứ hai.
 */
public final class EventPolicy {
  private static final Pattern NICKNAME = Pattern.compile("[A-Za-z0-9_.]{3,20}");
  private static final Pattern HMAC = Pattern.compile("[0-9a-f]{32}");
  private static final int MAX_DAILY_XP = 500;

  private EventPolicy() {}

  /** Trả lý do từ chối, hoặc rỗng nếu hợp lệ. */
  public static Optional<String> violation(Event event, Destination destination) {
    Optional<EventType> found = EventType.byId(event.getType());
    if (found.isEmpty()) {
      return Optional.of("loại sự kiện lạ");
    }
    EventType type = found.get();
    if (!type.source().equals(event.getSource())) {
      return Optional.of("sai nguồn phát");
    }
    if (!type.destinations().contains(destination)) {
      return Optional.of("sai đích nhận");
    }
    if (!type.matches(event.getPayloadCase())) {
      return Optional.of("payload không khớp loại");
    }
    if (!Uuids.isCanonical(event.getEventId())) {
      return Optional.of("event_id không hợp lệ");
    }
    return payloadValid(event, destination)
        ? Optional.empty()
        : Optional.of("payload sai ràng buộc");
  }

  private static boolean payloadValid(Event event, Destination destination) {
    return switch (event.getPayloadCase()) {
      case USER_REGISTERED -> {
        var p = event.getUserRegistered();
        yield user(destination, p.getUserId(), p.getUserHmac())
            && (destination != Destination.GAMIFICATION || nickname(p.getNickname()));
      }
      case USER_PROFILE_CHANGED -> {
        var p = event.getUserProfileChanged();
        yield user(destination, p.getUserId(), p.getUserHmac())
            && (destination != Destination.GAMIFICATION || nickname(p.getNickname()));
      }
      case USER_DELETED -> {
        var p = event.getUserDeleted();
        yield user(destination, p.getUserId(), p.getUserHmac());
      }
      case AUTH_LOGIN_FAILED -> optionalHmac(event.getAuthLoginFailed().getUserHmac());
      case AUTH_LOCKED -> optionalHmac(event.getAuthLocked().getUserHmac());
      case XP_RECORDED -> xp(event.getXpRecorded(), destination);
      case PROGRESS_CHANGED -> {
        var p = event.getProgressChanged();
        yield user(destination, p.getUserId(), p.getUserHmac())
            && p.getCurrentStreak() >= 0
            && p.getLongestStreak() >= 0;
      }
      case PAYLOAD_NOT_SET -> false;
    };
  }

  private static boolean xp(XpRecorded p, Destination destination) {
    return user(destination, p.getUserId(), p.getUserHmac())
        && p.getXpAwarded() >= 0
        && p.getXpAwarded() <= MAX_DAILY_XP
        && (destination != Destination.GAMIFICATION || Uuids.isCanonical(p.getXpUuid()));
  }

  /** insight chỉ được nhận bút danh; các đích khác nhận user_id (NFR-S13). */
  private static boolean user(Destination destination, String userId, String userHmac) {
    return destination == Destination.INSIGHT
        ? userId.isEmpty() && HMAC.matcher(userHmac).matches()
        : Uuids.isCanonical(userId);
  }

  private static boolean optionalHmac(String value) {
    return value.isEmpty() || HMAC.matcher(value).matches();
  }

  private static boolean nickname(String value) {
    return NICKNAME.matcher(value).matches();
  }
}

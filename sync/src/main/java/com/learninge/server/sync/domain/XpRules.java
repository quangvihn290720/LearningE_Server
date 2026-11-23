package com.learninge.server.sync.domain;

import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;
import com.learninge.server.common.time.VnTime;
import java.time.Duration;
import java.time.LocalDate;

/** Server tự tính XP, bỏ qua mọi con số XP app gửi (BR-065..BR-068). Hàm thuần. */
public final class XpRules {
  public static final int DAILY_CAP = 500;

  private static final long MAX_AGE_MILLIS = Duration.ofDays(7).toMillis();
  private static final long MAX_FUTURE_MILLIS = Duration.ofMinutes(5).toMillis();

  private XpRules() {}

  /** XP trước trần ngày; sự kiện vô lý hoặc ngoài khoảng thời gian → 0. */
  public static int baseXp(XpEventData event, long receivedAt) {
    long occurredAt = event.getOccurredAtMs();
    boolean inWindow =
        occurredAt >= receivedAt - MAX_AGE_MILLIS && occurredAt <= receivedAt + MAX_FUTURE_MILLIS;
    int correct = event.getCorrectCount();
    int wrong = event.getWrongCount();
    int total = event.getTotalCount();
    if (!inWindow || correct + wrong != total || total > maxItems(event.getSourceType())) {
      return 0;
    }
    return switch (event.getSourceType()) {
      case XP_SOURCE_TYPE_CARD_SESSION -> 2 * correct + wrong;
      case XP_SOURCE_TYPE_QUIZ -> total > 0 && correct == total ? correct + 5 : correct;
      case XP_SOURCE_TYPE_EXAM -> correct;
      default -> 0;
    };
  }

  /** Áp trần 500 XP/ngày theo thứ tự server nhận. */
  public static int capped(int baseXp, int alreadyAwardedThatDay) {
    return Math.max(0, Math.min(baseXp, DAILY_CAP - alreadyAwardedThatDay));
  }

  /** Ngày VN của sự kiện; thời điểm ở tương lai quá 5 phút thì dùng lúc server nhận. */
  public static LocalDate localDate(long occurredAt, long receivedAt) {
    return VnTime.date(occurredAt > receivedAt + MAX_FUTURE_MILLIS ? receivedAt : occurredAt);
  }

  private static int maxItems(XpSourceType type) {
    return switch (type) {
      case XP_SOURCE_TYPE_CARD_SESSION -> 50;
      case XP_SOURCE_TYPE_QUIZ -> 10;
      case XP_SOURCE_TYPE_EXAM -> 20;
      default -> 0;
    };
  }
}

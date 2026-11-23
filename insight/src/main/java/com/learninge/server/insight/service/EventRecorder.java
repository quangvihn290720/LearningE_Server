package com.learninge.server.insight.service;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.insight.store.AnalyticsStore;
import com.learninge.server.insight.store.AuditStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;

/**
 * Ghi nhật ký và cộng số liệu. Chỉ cộng khi dòng nhật ký được chèn mới, nên gửi trùng không đếm hai
 * lần.
 */
public final class EventRecorder {
  private final AuditStore audit;
  private final AnalyticsStore analytics;
  private final Clock clock;

  public EventRecorder(AuditStore audit, AnalyticsStore analytics, Clock clock) {
    this.audit = audit;
    this.analytics = analytics;
    this.clock = clock;
  }

  public synchronized void record(Event event) {
    Instant now = clock.instant();
    String userHmac = userHmac(event);
    if (event.hasUserDeleted()) {
      audit.purgeUser(userHmac, now);
      analytics.purgeUser(userHmac);
      userHmac = ""; // giữ dấu vết "có tài khoản bị xóa" nhưng không gắn với ai
    } else if (audit.isDeleted(userHmac)) {
      return;
    }
    if (audit.insertIfNew(auditDocument(event, userHmac))) {
      count(event, userHmac, now);
    }
  }

  private void count(Event event, String userHmac, Instant now) {
    String eventDate = VnTime.date(event.getCreatedAtMs()).toString();
    switch (event.getPayloadCase()) {
      case USER_REGISTERED -> analytics.add(eventDate, Map.of("new_users", 1), now);
      case AUTH_LOGIN_FAILED -> analytics.add(eventDate, Map.of("login_failed", 1), now);
      case AUTH_LOCKED -> analytics.add(eventDate, Map.of("locks", 1), now);
      case XP_RECORDED -> {
        var xp = event.getXpRecorded();
        analytics.add(xp.getLocalDate(), Map.of("xp_total", (long) xp.getXpAwarded()), now);
      }
      case PROGRESS_CHANGED -> {
        var p = event.getProgressChanged();
        Map<String, Number> increments = new LinkedHashMap<>();
        increments.put("push_count", 1);
        increments.put("push_records", p.getRecords());
        increments.put("cards_reviewed", p.getCards());
        increments.put("quiz_count", p.getQuizzes());
        increments.put("exam_count", p.getExams());
        if (analytics.markActive(p.getDate(), userHmac, now)) {
          increments.put("dau", 1);
        }
        analytics.add(p.getDate(), increments, now);
      }
      case USER_PROFILE_CHANGED, USER_DELETED, PAYLOAD_NOT_SET -> {
        // chỉ cần dòng nhật ký
      }
    }
  }

  private static Document auditDocument(Event event, String userHmac) {
    Document document =
        new Document("_id", event.getEventId())
            .append("type", event.getType())
            .append("source", event.getSource())
            .append("occurred_at", new Date(event.getCreatedAtMs()));
    if (!userHmac.isEmpty()) {
      document.append("user_hmac", userHmac);
    }
    Document attrs = attributes(event);
    if (!attrs.isEmpty()) {
      document.append("attrs", attrs);
    }
    return document;
  }

  private static Document attributes(Event event) {
    return switch (event.getPayloadCase()) {
      case AUTH_LOGIN_FAILED -> new Document("scope", event.getAuthLoginFailed().getScope().name());
      case AUTH_LOCKED ->
          new Document("scope", event.getAuthLocked().getScope().name())
              .append("lock_level", event.getAuthLocked().getLockLevel())
              .append("lock_seconds", event.getAuthLocked().getLockSeconds());
      case XP_RECORDED ->
          new Document("source_type", event.getXpRecorded().getSourceType())
              .append("xp", event.getXpRecorded().getXpAwarded());
      case PROGRESS_CHANGED -> new Document("records", event.getProgressChanged().getRecords());
      default -> new Document();
    };
  }

  private static String userHmac(Event event) {
    return switch (event.getPayloadCase()) {
      case USER_REGISTERED -> event.getUserRegistered().getUserHmac();
      case USER_PROFILE_CHANGED -> event.getUserProfileChanged().getUserHmac();
      case USER_DELETED -> event.getUserDeleted().getUserHmac();
      case AUTH_LOGIN_FAILED -> event.getAuthLoginFailed().getUserHmac();
      case AUTH_LOCKED -> event.getAuthLocked().getUserHmac();
      case XP_RECORDED -> event.getXpRecorded().getUserHmac();
      case PROGRESS_CHANGED -> event.getProgressChanged().getUserHmac();
      case PAYLOAD_NOT_SET -> "";
    };
  }
}

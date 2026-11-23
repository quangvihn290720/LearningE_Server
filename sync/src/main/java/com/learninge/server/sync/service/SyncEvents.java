package com.learninge.server.sync.service;

import com.learninge.proto.internal.v1.ProgressChanged;
import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventType;
import com.learninge.server.common.outbox.OutboxWriter;
import com.learninge.server.common.security.UserPseudonym;
import com.learninge.server.sync.store.UserStatsStore.UserStats;
import com.learninge.server.sync.store.XpEventTable.ScoredXp;
import java.time.LocalDate;

/** Sự kiện của sync, payload cắt theo đích. */
public final class SyncEvents {
  /** Số bản ghi APPLIED trong một lô, dùng cho số liệu phân tích. */
  record BatchCounts(int cards, int quizzes, int exams, int records) {}

  private final OutboxWriter outbox;
  private final UserPseudonym pseudonym;

  public SyncEvents(OutboxWriter outbox, UserPseudonym pseudonym) {
    this.outbox = outbox;
    this.pseudonym = pseudonym;
  }

  void xpRecorded(String userId, ScoredXp xp) {
    outbox.append(
        EventType.XP_RECORDED,
        xp.uuid(),
        (destination, event) -> {
          XpRecorded.Builder payload =
              XpRecorded.newBuilder()
                  .setSourceType(xp.sourceType())
                  .setXpAwarded(xp.awarded())
                  .setLocalDate(xp.localDate().toString());
          if (destination == Destination.INSIGHT) {
            payload.setUserHmac(pseudonym.of(userId));
          } else {
            payload
                .setXpUuid(xp.uuid())
                .setUserId(userId)
                .setOccurredAtMs(xp.occurredAt())
                .setWeekStart(xp.weekStart().toString())
                .setServerSeq(xp.seq())
                .setReceivedAtMs(xp.receivedAt());
          }
          event.setXpRecorded(payload);
        });
  }

  void progressChanged(String userId, UserStats stats, LocalDate today, BatchCounts counts) {
    outbox.append(
        EventType.PROGRESS_CHANGED,
        userId,
        (destination, event) -> {
          ProgressChanged.Builder payload = ProgressChanged.newBuilder();
          if (destination == Destination.INSIGHT) {
            payload
                .setUserHmac(pseudonym.of(userId))
                .setDate(today.toString())
                .setCards(counts.cards())
                .setQuizzes(counts.quizzes())
                .setExams(counts.exams())
                .setRecords(counts.records());
          } else {
            LocalDate last = stats.streak().lastStudyDate();
            payload
                .setUserId(userId)
                .setCurrentStreak(stats.streak().current())
                .setLongestStreak(stats.streak().longest())
                .setLastStudyDate(last == null ? "" : last.toString())
                .setVersion(stats.version());
          }
          event.setProgressChanged(payload);
        });
  }
}

package com.learninge.server.sync.service;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.server.common.id.UuidV5;
import com.learninge.server.common.id.Uuids;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.sync.domain.RecordRules;
import com.learninge.server.sync.domain.SyncClock;
import com.learninge.server.sync.domain.XpRules;
import com.learninge.server.sync.service.Outcome.Applied;
import com.learninge.server.sync.service.Outcome.Duplicate;
import com.learninge.server.sync.service.Outcome.Reason;
import com.learninge.server.sync.service.Outcome.Rejected;
import com.learninge.server.sync.service.Outcome.Stale;
import com.learninge.server.sync.store.DeckTable.DeckRef;
import com.learninge.server.sync.store.SyncTable;
import com.learninge.server.sync.store.SyncTables;
import com.learninge.server.sync.store.XpEventTable;
import com.learninge.server.sync.store.XpEventTable.ScoredXp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ghi một bản ghi trong giao dịch của lô. Luật chung: kiểm miền giá trị → kiểm chủ sở hữu (bản ghi
 * của người khác coi như không hợp lệ, không trả dữ liệu) → cấp {@code server_seq} → ghi.
 */
public final class RecordWriter {
  private static final Rejected INVALID = new Rejected(Reason.INVALID_RECORD);

  private final SyncTables tables;

  public RecordWriter(SyncTables tables) {
    this.tables = tables;
  }

  Outcome write(String userId, Change change, long now) {
    if (change.getDataCase() == Change.DataCase.DATA_NOT_SET) {
      return new Rejected(Reason.UNKNOWN_ENTITY);
    }
    if (change.getUpdatedAtMs() <= 0) {
      return INVALID;
    }
    long updatedAt = SyncClock.clamp(change.getUpdatedAtMs(), now);
    return switch (change.getDataCase()) {
      case SETTINGS -> settings(userId, change, updatedAt);
      case DECK -> deck(userId, change, updatedAt);
      case WORD -> word(userId, change, updatedAt);
      case CARD_PROGRESS -> cardProgress(userId, change, updatedAt);
      case FAVORITE -> favorite(userId, change, updatedAt);
      case STUDY_LOG -> studyLog(userId, change, updatedAt);
      case QUIZ_ATTEMPT -> quizAttempt(userId, change, updatedAt);
      case EXAM_ATTEMPT -> examAttempt(userId, change, updatedAt);
      case XP_EVENT -> xpEvent(userId, change, now);
      case DATA_NOT_SET -> throw new IllegalStateException("đã xử lý ở trên");
    };
  }

  private Outcome settings(String userId, Change change, long updatedAt) {
    if (change.getDeleted() || !RecordRules.settings(change.getSettings())) {
      return INVALID;
    }
    long seq = tables.nextSeq();
    tables.settings.upsert(writer(), userId, change.getSettings(), updatedAt, seq);
    return Applied.at(seq);
  }

  private Outcome deck(String userId, Change change, long updatedAt) {
    String uuid = change.getRecordId();
    boolean isNotebook = uuid.equals(UuidV5.notebook(userId));
    if (!Uuids.isCanonical(uuid) || (isNotebook && change.getDeleted())) {
      return INVALID; // BR-045: không xóa được "Sổ tay của tôi"
    }
    Optional<DeckRef> existing = tables.decks.find(writer(), uuid);
    if (existing.isPresent() && !existing.get().userId().equals(userId)) {
      return INVALID;
    }
    if (change.getDeleted() && existing.isPresent()) {
      long seq = tables.nextSeq();
      tables.decks.markDeleted(writer(), userId, uuid, updatedAt, seq);
      return Applied.at(seq);
    }
    if (!RecordRules.deck(change.getDeck())) {
      return INVALID;
    }
    long seq = tables.nextSeq();
    tables.decks.upsert(
        writer(), userId, uuid, change.getDeck(), isNotebook, change.getDeleted(), updatedAt, seq);
    return Applied.at(seq);
  }

  private Outcome word(String userId, Change change, long updatedAt) {
    String uuid = change.getRecordId();
    if (!Uuids.isCanonical(uuid)) {
      return INVALID;
    }
    String owner = tables.words.ownerOf(writer(), uuid);
    if (owner != null && !owner.equals(userId)) {
      return INVALID;
    }
    if (change.getDeleted() && owner != null) {
      long seq = tables.nextSeq();
      tables.words.markDeleted(writer(), userId, uuid, updatedAt, seq);
      return Applied.at(seq);
    }
    if (!RecordRules.word(change.getWord())) {
      return INVALID;
    }
    Optional<DeckRef> deck = tables.decks.find(writer(), change.getWord().getDeckUuid());
    boolean usable =
        deck.isPresent()
            && deck.get().userId().equals(userId)
            && (!deck.get().deleted() || change.getDeleted());
    if (!usable) {
      return new Rejected(Reason.DECK_NOT_OWNED);
    }
    long seq = tables.nextSeq();
    tables.words.upsert(
        writer(), userId, uuid, change.getWord(), change.getDeleted(), updatedAt, seq);
    return Applied.at(seq);
  }

  private Outcome cardProgress(String userId, Change change, long updatedAt) {
    String wordUid = change.getRecordId();
    if (change.getDeleted()
        || !ownsWord(userId, wordUid)
        || !RecordRules.cardProgress(change.getCardProgress())) {
      return INVALID;
    }
    if (tables.cardProgress.updatedAt(writer(), userId, wordUid) >= updatedAt) {
      return new Stale(tables.cardProgress.find(writer(), userId, wordUid));
    }
    long seq = tables.nextSeq();
    tables.cardProgress.upsertIfNewer(
        writer(), userId, wordUid, change.getCardProgress(), updatedAt, seq);
    return Applied.at(seq);
  }

  private Outcome favorite(String userId, Change change, long updatedAt) {
    String wordUid = change.getRecordId();
    if (!ownsWord(userId, wordUid) || !RecordRules.favorite(change.getFavorite())) {
      return INVALID;
    }
    long seq = tables.nextSeq();
    tables.favorites.upsert(
        writer(), userId, wordUid, change.getFavorite(), change.getDeleted(), updatedAt, seq);
    return Applied.at(seq);
  }

  private Outcome studyLog(String userId, Change change, long updatedAt) {
    var data = change.getStudyLog();
    return append(
        userId,
        change,
        tables.studyLog,
        RecordRules.studyLog(data),
        seq ->
            tables.studyLog.insert(writer(), userId, change.getRecordId(), data, updatedAt, seq));
  }

  private Outcome quizAttempt(String userId, Change change, long updatedAt) {
    var data = change.getQuizAttempt();
    return append(
        userId,
        change,
        tables.quizAttempts,
        RecordRules.quizAttempt(data),
        seq ->
            tables.quizAttempts.insert(
                writer(), userId, change.getRecordId(), data, updatedAt, seq));
  }

  private Outcome examAttempt(String userId, Change change, long updatedAt) {
    var data = change.getExamAttempt();
    if (!RecordRules.testCode(data.getTestCode())) {
      return new Rejected(Reason.UNKNOWN_TEST);
    }
    return append(
        userId,
        change,
        tables.examAttempts,
        RecordRules.examAttempt(data),
        seq ->
            tables.examAttempts.insert(
                writer(), userId, change.getRecordId(), data, updatedAt, seq));
  }

  /** Bảng chỉ-thêm: đã có thì DUPLICATE (gửi lại cùng lô không đổi gì). */
  private Outcome append(
      String userId, Change change, SyncTable table, boolean valid, LongConsumer insert) {
    String uuid = change.getRecordId();
    if (change.getDeleted() || !Uuids.isCanonical(uuid) || !valid) {
      return INVALID;
    }
    String owner = table.ownerOf(writer(), uuid);
    if (owner != null) {
      return owner.equals(userId) ? new Duplicate(table.seqOf(writer(), uuid), null) : INVALID;
    }
    long seq = tables.nextSeq();
    insert.accept(seq);
    return Applied.at(seq);
  }

  private Outcome xpEvent(String userId, Change change, long now) {
    String uuid = change.getRecordId();
    XpEventData data = change.getXpEvent();
    if (change.getDeleted() || !Uuids.isCanonical(uuid) || !RecordRules.xpEvent(data)) {
      return INVALID;
    }
    String owner = tables.xpEvents.ownerOf(writer(), uuid);
    if (owner != null) {
      if (!owner.equals(userId)) {
        return INVALID;
      }
      Change current = tables.xpEvents.find(writer(), uuid);
      return new Duplicate(current.getServerSeq(), current);
    }
    LocalDate day = XpRules.localDate(data.getOccurredAtMs(), now);
    int awarded =
        XpRules.capped(XpRules.baseXp(data, now), tables.xpEvents.awardedOn(writer(), userId, day));
    ScoredXp scored =
        new ScoredXp(
            uuid,
            XpEventTable.sourceName(data.getSourceType()),
            awarded,
            data.getOccurredAtMs(),
            now,
            day,
            VnTime.weekStart(day),
            tables.nextSeq());
    tables.xpEvents.insert(writer(), userId, data, scored);
    return new Applied(scored.seq(), tables.xpEvents.find(writer(), uuid), scored);
  }

  /** Từ có sẵn ai cũng dùng được; từ riêng thì không được là của người khác. */
  private boolean ownsWord(String userId, String wordUid) {
    if (!RecordRules.wordUid(wordUid)) {
      return false;
    }
    if (!RecordRules.isUserWord(wordUid)) {
      return true;
    }
    String owner = tables.words.ownerOf(writer(), wordUid);
    return owner == null || owner.equals(userId);
  }

  private JdbcClient writer() {
    return tables.writer();
  }
}

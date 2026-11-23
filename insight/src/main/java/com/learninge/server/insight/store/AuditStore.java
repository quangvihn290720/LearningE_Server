package com.learninge.server.insight.store;

import com.learninge.server.common.mongo.MongoErrors;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;

/** Nhật ký sự kiện, nhật ký yêu cầu và dấu người dùng đã xóa; chỉ có bút danh, không có PII. */
public final class AuditStore {
  /** Giữ dấu lâu hơn mọi khoảng gửi lại của outbox (7 ngày) và requeue thủ công. */
  private static final Duration TOMBSTONE_TTL = Duration.ofDays(120);

  private final MongoCollection<Document> audit;
  private final MongoCollection<Document> requests;
  private final MongoCollection<Document> deleted;

  public AuditStore(MongoDatabase database) {
    this.audit = database.getCollection("audit_events");
    this.requests = database.getCollection("request_logs");
    this.deleted = database.getCollection("deleted_users");
  }

  /** Trả false nếu sự kiện đã ghi trước đó ({@code _id = event_id}). */
  public boolean insertIfNew(Document event) {
    try {
      audit.insertOne(event);
      return true;
    } catch (MongoWriteException e) {
      if (MongoErrors.isDuplicateKey(e)) {
        return false;
      }
      throw e;
    }
  }

  public void insertRequests(List<Document> logs) {
    if (!logs.isEmpty()) {
      requests.insertMany(logs);
    }
  }

  public boolean isDeleted(String userHmac) {
    return !userHmac.isEmpty() && !deletedAmong(Set.of(userHmac)).isEmpty();
  }

  public Set<String> deletedAmong(Collection<String> userHmacs) {
    Set<String> found = new HashSet<>();
    deleted.find(Filters.in("_id", userHmacs)).forEach(d -> found.add(d.getString("_id")));
    return found;
  }

  /** Ghi dấu trước rồi mới xóa, để dữ liệu đến muộn không dựng lại được. */
  public void purgeUser(String userHmac, Instant now) {
    deleted.updateOne(
        Filters.eq("_id", userHmac),
        Updates.setOnInsert("expire_at", Date.from(now.plus(TOMBSTONE_TTL))),
        new UpdateOptions().upsert(true));
    audit.deleteMany(Filters.eq("user_hmac", userHmac));
    requests.deleteMany(Filters.eq("user_hmac", userHmac));
  }
}

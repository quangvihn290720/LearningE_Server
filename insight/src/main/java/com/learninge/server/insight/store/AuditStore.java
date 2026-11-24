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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;

/**
 * Nhật ký sự kiện, nhật ký yêu cầu và dấu người dùng đã xóa; chỉ có bút danh, không có PII.
 */
public final class AuditStore {
    /**
     * Giữ dấu lâu hơn mọi khoảng gửi lại của outbox (7 ngày) và requeue thủ công.
     */
    private static final Duration TOMBSTONE_TTL = Duration.ofDays(120);

    /**
     * UNKNOWN, DEADLINE_EXCEEDED, INTERNAL, UNAVAILABLE, DATA_LOSS.
     */
    private static final List<Integer> SERVER_ERRORS = List.of(2, 4, 13, 14, 15);

    private final MongoCollection<Document> audit;
    private final MongoCollection<Document> requests;
    private final MongoCollection<Document> deleted;

    public AuditStore(MongoDatabase database) {
        this.audit = database.getCollection("audit_events");
        this.requests = database.getCollection("request_logs");
        this.deleted = database.getCollection("deleted_users");
    }

    /**
     * Trả false nếu sự kiện đã ghi trước đó ({@code _id = event_id}).
     */
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

    /**
     * Mỗi RPC một dòng, gọi nhiều nhất trước; mọi dòng còn lưu (7 ngày).
     */
    public List<Document> rpcStats() {
        Document isServerError = new Document("$in", List.of("$code", SERVER_ERRORS));
        Document isOtherError =
                new Document(
                        "$and",
                        List.of(
                                new Document("$ne", List.of("$code", 0)),
                                new Document("$not", List.of(isServerError))));
        Document percentiles =
                new Document(
                        "$percentile",
                        new Document("input", "$duration_ms")
                                .append("p", List.of(0.5, 0.95))
                                .append("method", "approximate"));
        Document group =
                new Document("_id", "$rpc")
                        .append("calls", new Document("$sum", 1))
                        .append("server_errors", count(isServerError))
                        .append("other_errors", count(isOtherError))
                        .append("times", percentiles)
                        .append("slowest", new Document("$max", "$duration_ms"));
        return requests
                .aggregate(
                        List.of(
                                new Document("$group", group),
                                new Document("$sort", new Document("calls", -1).append("_id", 1))))
                .into(new ArrayList<>());
    }

    private static Document count(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    public boolean isDeleted(String userHmac) {
        return !userHmac.isEmpty() && !deletedAmong(Set.of(userHmac)).isEmpty();
    }

    public Set<String> deletedAmong(Collection<String> userHmacs) {
        Set<String> found = new HashSet<>();
        deleted.find(Filters.in("_id", userHmacs)).forEach(d -> found.add(d.getString("_id")));
        return found;
    }

    /**
     * Ghi dấu trước rồi mới xóa, để dữ liệu đến muộn không dựng lại được.
     */
    public void purgeUser(String userHmac, Instant now) {
        deleted.updateOne(
                Filters.eq("_id", userHmac),
                Updates.setOnInsert("expire_at", Date.from(now.plus(TOMBSTONE_TTL))),
                new UpdateOptions().upsert(true));
        audit.deleteMany(Filters.eq("user_hmac", userHmac));
        requests.deleteMany(Filters.eq("user_hmac", userHmac));
    }
}

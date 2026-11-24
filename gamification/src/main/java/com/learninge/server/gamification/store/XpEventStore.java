package com.learninge.server.gamification.store;

import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.gamification.domain.WeeklyTotal;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.bson.Document;

/**
 * Bản sao sự kiện XP từ sync; {@code _id} là uuid sự kiện nên ghi lại không nhân đôi.
 */
public final class XpEventStore {
    /**
     * Một sự kiện XP và mã sự kiện outbox đã mang nó tới.
     */
    public record XpEvent(XpRecorded xp, String eventId) {
    }

    private final MongoCollection<Document> events;

    public XpEventStore(MongoDatabase database) {
        this.events = database.getCollection("xp_events");
    }

    /**
     * Một lượt gửi cho cả cụm. Thứ tự không quan trọng: mỗi sự kiện một tài liệu.
     */
    public void insertIfAbsent(List<XpEvent> batch) {
        List<UpdateOneModel<Document>> writes =
                batch.stream().map(e -> upsert(e.xp(), e.eventId())).toList();
        events.bulkWrite(writes, new BulkWriteOptions().ordered(false));
    }

    private static UpdateOneModel<Document> upsert(XpRecorded xp, String eventId) {
        Document fields =
                new Document("user_id", xp.getUserId())
                        .append("source_type", xp.getSourceType())
                        .append("xp_awarded", xp.getXpAwarded())
                        .append("occurred_at", new Date(xp.getOccurredAtMs()))
                        .append("received_at", new Date(xp.getReceivedAtMs()))
                        .append("local_date", xp.getLocalDate())
                        .append("week_start", xp.getWeekStart())
                        .append("server_seq", xp.getServerSeq())
                        .append("event_id", eventId);
        return new UpdateOneModel<>(
                Filters.eq("_id", xp.getXpUuid()),
                new Document("$setOnInsert", fields),
                new UpdateOptions().upsert(true));
    }

    /**
     * Mỗi ngày một dòng, cộng sẵn ở MongoDB; {@code receivedAt} là sự kiện có XP muộn nhất trong ngày.
     */
    public List<WeeklyTotal.Entry> week(String userId, String weekStart) {
        Document lastWithXp =
                new Document(
                        "$cond",
                        Arrays.asList(
                                new Document("$gt", List.of("$xp_awarded", 0)), "$received_at", null));
        return events
                .aggregate(
                        List.of(
                                Aggregates.match(
                                        Filters.and(
                                                Filters.eq("user_id", userId), Filters.eq("week_start", weekStart))),
                                Aggregates.group(
                                        "$local_date",
                                        Accumulators.sum("xp", "$xp_awarded"),
                                        Accumulators.max("last", lastWithXp))))
                .map(XpEventStore::day)
                .into(new ArrayList<>());
    }

    private static WeeklyTotal.Entry day(Document d) {
        Date last = d.getDate("last");
        return new WeeklyTotal.Entry(
                d.getString("_id"),
                ((Number) d.get("xp")).intValue(),
                last == null ? Instant.EPOCH : last.toInstant());
    }

    public long totalXp(String userId) {
        Document result =
                events
                        .aggregate(
                                List.of(
                                        Aggregates.match(Filters.eq("user_id", userId)),
                                        Aggregates.group(null, Accumulators.sum("total", "$xp_awarded"))))
                        .first();
        return result == null ? 0 : ((Number) result.get("total")).longValue();
    }

    public void deleteUser(String userId) {
        events.deleteMany(Filters.eq("user_id", userId));
    }
}

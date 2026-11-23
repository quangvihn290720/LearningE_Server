package com.learninge.server.gamification.store;

import com.learninge.proto.internal.v1.XpRecorded;
import com.learninge.server.gamification.domain.WeeklyTotal;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;

/** Bản sao sự kiện XP từ sync; {@code _id} là uuid sự kiện nên ghi lại không nhân đôi. */
public final class XpEventStore {
  private final MongoCollection<Document> events;

  public XpEventStore(MongoDatabase database) {
    this.events = database.getCollection("xp_events");
  }

  public void insertIfAbsent(XpRecorded xp, String eventId) {
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
    events.updateOne(
        Filters.eq("_id", xp.getXpUuid()),
        new Document("$setOnInsert", fields),
        new UpdateOptions().upsert(true));
  }

  public List<WeeklyTotal.Entry> week(String userId, String weekStart) {
    return events
        .find(Filters.and(Filters.eq("user_id", userId), Filters.eq("week_start", weekStart)))
        .map(
            d ->
                new WeeklyTotal.Entry(
                    d.getString("local_date"),
                    d.getInteger("xp_awarded"),
                    d.getDate("received_at").toInstant()))
        .into(new ArrayList<>());
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

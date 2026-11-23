package com.learninge.server.insight.store;

import com.learninge.server.common.mongo.MongoErrors;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;

/** Số liệu theo ngày; {@code analytics_daily} không chứa gì định danh người dùng. */
public final class AnalyticsStore {
  private static final Duration DAU_TTL = Duration.ofDays(35);

  private final MongoCollection<Document> daily;
  private final MongoCollection<Document> dailyUsers;

  public AnalyticsStore(MongoDatabase database) {
    this.daily = database.getCollection("analytics_daily");
    this.dailyUsers = database.getCollection("analytics_daily_users");
  }

  public void add(String date, Map<String, Number> increments, Instant now) {
    List<Bson> updates = new ArrayList<>();
    increments.forEach((field, amount) -> updates.add(Updates.inc(field, amount)));
    updates.add(Updates.set("updated_at", Date.from(now)));
    daily.updateOne(
        Filters.eq("_id", date), Updates.combine(updates), new UpdateOptions().upsert(true));
  }

  /** Trả true nếu đây là lần đầu thấy người này trong ngày. */
  public boolean markActive(String date, String userHmac, Instant now) {
    try {
      dailyUsers.insertOne(
          new Document("_id", new Document("d", date).append("u", userHmac))
              .append("expire_at", Date.from(now.plus(DAU_TTL))));
      return true;
    } catch (MongoWriteException e) {
      if (MongoErrors.isDuplicateKey(e)) {
        return false;
      }
      throw e;
    }
  }

  public List<Document> between(String from, String to) {
    return daily
        .find(Filters.and(Filters.gte("_id", from), Filters.lte("_id", to)))
        .sort(Sorts.ascending("_id"))
        .into(new ArrayList<>());
  }

  public void purgeUser(String userHmac) {
    dailyUsers.deleteMany(Filters.eq("_id.u", userHmac));
  }
}

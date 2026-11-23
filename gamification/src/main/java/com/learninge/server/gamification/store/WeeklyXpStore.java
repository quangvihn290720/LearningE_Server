package com.learninge.server.gamification.store;

import com.learninge.server.gamification.domain.WeeklyTotal;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;

/** XP tuần của từng người, đã sắp sẵn bằng index để lấy bảng xếp hạng. */
public final class WeeklyXpStore {
  public record Row(String userId, int xp, Instant reachedAt) {}

  private final MongoCollection<Document> weekly;

  public WeeklyXpStore(MongoDatabase database) {
    this.weekly = database.getCollection("weekly_xp");
  }

  /**
   * XP tuần chỉ tăng, nên dùng {@code $max}: hai lượt tính chạy xen kẽ (vd rebuild lúc đang nhận sự
   * kiện) không thể ghi đè số lớn bằng số nhỏ.
   */
  public void raise(String userId, String weekStart, WeeklyTotal total, Instant now) {
    weekly.updateOne(
        Filters.eq("_id", weekStart + ":" + userId),
        Updates.combine(
            Updates.max("xp", total.xp()),
            Updates.max("reached_at", Date.from(total.reachedAt())),
            Updates.set("updated_at", Date.from(now)),
            Updates.setOnInsert("week_start", weekStart),
            Updates.setOnInsert("user_id", userId)),
        new UpdateOptions().upsert(true));
  }

  public List<Row> top(String weekStart, int limit) {
    return weekly
        .find(Filters.and(Filters.eq("week_start", weekStart), Filters.gt("xp", 0)))
        .sort(Sorts.orderBy(Sorts.descending("xp"), Sorts.ascending("reached_at", "user_id")))
        .limit(limit)
        .map(WeeklyXpStore::row)
        .into(new ArrayList<>());
  }

  public Optional<Row> find(String userId, String weekStart) {
    return Optional.ofNullable(weekly.find(Filters.eq("_id", weekStart + ":" + userId)).first())
        .map(WeeklyXpStore::row);
  }

  /** Hạng = số người đứng trên + 1, cùng luật so sánh với {@link #top}. */
  public int rankOf(Row me, String weekStart) {
    Date reachedAt = Date.from(me.reachedAt());
    Bson above =
        Filters.or(
            Filters.gt("xp", me.xp()),
            Filters.and(Filters.eq("xp", me.xp()), Filters.lt("reached_at", reachedAt)),
            Filters.and(
                Filters.eq("xp", me.xp()),
                Filters.eq("reached_at", reachedAt),
                Filters.lt("user_id", me.userId())));
    return (int) weekly.countDocuments(Filters.and(Filters.eq("week_start", weekStart), above)) + 1;
  }

  public void deleteUser(String userId) {
    weekly.deleteMany(Filters.eq("user_id", userId));
  }

  private static Row row(Document d) {
    return new Row(d.getString("user_id"), d.getInteger("xp"), d.getDate("reached_at").toInstant());
  }
}

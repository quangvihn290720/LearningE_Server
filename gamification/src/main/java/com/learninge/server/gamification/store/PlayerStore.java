package com.learninge.server.gamification.store;

import com.learninge.server.common.mongo.MongoErrors;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Biệt danh, chuỗi ngày, tổng XP và dấu người dùng đã xóa.
 */
public final class PlayerStore {
    public record Player(String nickname, int currentStreak, long totalXp) {
    }

    private final MongoCollection<Document> players;
    private final MongoCollection<Document> deleted;

    public PlayerStore(MongoDatabase database) {
        this.players = database.getCollection("user_stats");
        this.deleted = database.getCollection("deleted_users");
    }

    public void saveNickname(String userId, String nickname, long version, Instant now) {
        updateIfNewer(
                userId,
                "profile_version",
                version,
                Updates.combine(
                        Updates.set("nickname", nickname), Updates.set("updated_at", Date.from(now))));
    }

    public void saveStreak(String userId, int current, int longest, long version, Instant now) {
        updateIfNewer(
                userId,
                "streak_version",
                version,
                Updates.combine(
                        Updates.set("current_streak", current),
                        Updates.set("longest_streak", longest),
                        Updates.set("updated_at", Date.from(now))));
    }

    /**
     * Tổng XP chỉ tăng, xem {@link WeeklyXpStore#raise}.
     */
    public void raiseTotalXp(String userId, long total, Instant now) {
        players.updateOne(
                Filters.eq("_id", userId),
                Updates.combine(Updates.max("total_xp", total), Updates.set("updated_at", Date.from(now))),
                new UpdateOptions().upsert(true));
    }

    public Optional<Player> find(String userId) {
        return Optional.ofNullable(players.find(Filters.eq("_id", userId)).first())
                .map(PlayerStore::player);
    }

    public Map<String, Player> findAll(Collection<String> userIds) {
        Map<String, Player> found = new HashMap<>();
        players.find(Filters.in("_id", userIds)).forEach(d -> found.put(d.getString("_id"), player(d)));
        return found;
    }

    public boolean isDeleted(String userId) {
        return deleted.find(Filters.eq("_id", userId)).first() != null;
    }

    /**
     * Ghi dấu trước khi xóa, để sự kiện đến muộn không dựng lại dữ liệu (TM-39).
     */
    public void markDeleted(String userId, Instant now) {
        deleted.updateOne(
                Filters.eq("_id", userId),
                Updates.setOnInsert("deleted_at", Date.from(now)),
                new UpdateOptions().upsert(true));
    }

    public void deleteUser(String userId) {
        players.deleteOne(Filters.eq("_id", userId));
    }

    /**
     * Chỉ ghi khi phiên bản mới hơn; sự kiện cũ đến muộn rơi vào trùng khóa và bị bỏ.
     */
    private void updateIfNewer(String userId, String versionField, long version, Bson changes) {
        Bson older = Filters.or(Filters.exists(versionField, false), Filters.lt(versionField, version));
        try {
            players.updateOne(
                    Filters.and(Filters.eq("_id", userId), older),
                    Updates.combine(changes, Updates.set(versionField, version)),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (!MongoErrors.isDuplicateKey(e)) {
                throw e;
            }
        }
    }

    private static Player player(Document d) {
        Long total = d.getLong("total_xp");
        return new Player(
                d.getString("nickname"), d.getInteger("current_streak", 0), total == null ? 0 : total);
    }
}

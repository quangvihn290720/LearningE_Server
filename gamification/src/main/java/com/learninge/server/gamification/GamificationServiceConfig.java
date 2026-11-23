package com.learninge.server.gamification;

import com.learninge.server.common.config.InternalServerConfiguration;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.mongo.CollectionSpec;
import com.learninge.server.common.mongo.MongoConfiguration;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.gamification.grpc.GamificationBackendService;
import com.learninge.server.gamification.grpc.GamificationEventSink;
import com.learninge.server.gamification.service.GameUpdates;
import com.learninge.server.gamification.service.LeaderboardService;
import com.learninge.server.gamification.service.RebuildCommand;
import com.learninge.server.gamification.store.PlayerStore;
import com.learninge.server.gamification.store.WeeklyXpStore;
import com.learninge.server.gamification.store.XpEventStore;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Dây nối của dịch vụ gamification. */
@Configuration(proxyBeanMethods = false)
@Import({InternalServerConfiguration.class, MongoConfiguration.class})
public class GamificationServiceConfig {

  @Bean
  MongoSchema mongoSchema() {
    return new MongoSchema(
        GamificationApplication.NAME,
        List.of(
            CollectionSpec.of(
                "xp_events",
                new IndexModel(
                    Indexes.ascending("user_id", "week_start", "local_date"),
                    new IndexOptions().name("ix_xp_user_week"))),
            CollectionSpec.of(
                "weekly_xp",
                new IndexModel(
                    Indexes.compoundIndex(
                        Indexes.ascending("week_start"),
                        Indexes.descending("xp"),
                        Indexes.ascending("reached_at", "user_id")),
                    new IndexOptions().name("ix_weekly_rank")),
                new IndexModel(
                    Indexes.compoundIndex(
                        Indexes.ascending("user_id"), Indexes.descending("week_start")),
                    new IndexOptions().name("ix_weekly_user"))),
            CollectionSpec.of("user_stats"),
            CollectionSpec.of("deleted_users")));
  }

  @Bean
  XpEventStore xpEventStore(MongoDatabase database) {
    return new XpEventStore(database);
  }

  @Bean
  WeeklyXpStore weeklyXpStore(MongoDatabase database) {
    return new WeeklyXpStore(database);
  }

  @Bean
  PlayerStore playerStore(MongoDatabase database) {
    return new PlayerStore(database);
  }

  @Bean
  GameUpdates gameUpdates(
      XpEventStore xpEvents, WeeklyXpStore weekly, PlayerStore players, Clock clock) {
    return new GameUpdates(xpEvents, weekly, players, clock);
  }

  @Bean
  LeaderboardService leaderboardService(WeeklyXpStore weekly, PlayerStore players, Clock clock) {
    return new LeaderboardService(weekly, players, clock);
  }

  @Bean
  GamificationBackendService gamificationBackendService(LeaderboardService leaderboard) {
    return new GamificationBackendService(leaderboard);
  }

  @Bean
  GamificationEventSink gamificationEventSink(GameUpdates updates) {
    return new GamificationEventSink(updates);
  }

  @Bean
  RebuildCommand rebuildCommand(
      InternalChannels channels, GameUpdates updates, MongoSchema schema, MongoDatabase database) {
    return new RebuildCommand(channels, updates, schema, database);
  }
}

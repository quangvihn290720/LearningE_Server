package com.learninge.server.gamification.service;

import com.learninge.proto.app.v1.GetWeeklyResponse;
import com.learninge.proto.app.v1.LeaderboardEntry;
import com.learninge.proto.app.v1.WeekSelector;
import com.learninge.proto.internal.v1.GetStatsResponse;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.gamification.store.PlayerStore;
import com.learninge.server.gamification.store.PlayerStore.Player;
import com.learninge.server.gamification.store.WeeklyXpStore;
import com.learninge.server.gamification.store.WeeklyXpStore.Row;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** EP-13: top 50 XP tuần và dòng "Bạn". Không trả email hay id của người khác. */
public final class LeaderboardService {
  private static final int TOP = 50;
  private static final Player UNKNOWN = new Player(null, 0, 0);

  private final WeeklyXpStore weekly;
  private final PlayerStore players;
  private final Clock clock;

  public LeaderboardService(WeeklyXpStore weekly, PlayerStore players, Clock clock) {
    this.weekly = weekly;
    this.players = players;
    this.clock = clock;
  }

  public GetWeeklyResponse weekly(String userId, WeekSelector selector) {
    LocalDate week = weekOf(selector);
    List<Row> top = weekly.top(week.toString(), TOP);
    Map<String, Player> found = players.findAll(top.stream().map(Row::userId).toList());
    GetWeeklyResponse.Builder response =
        GetWeeklyResponse.newBuilder()
            .setWeekStart(week.toString())
            .setWeekEnd(week.plusDays(6).toString())
            .setTimezone(VnTime.ZONE.getId())
            .setGeneratedAtMs(clock.millis());
    for (int i = 0; i < top.size(); i++) {
      Row row = top.get(i);
      Player player = found.getOrDefault(row.userId(), UNKNOWN);
      // Chưa nhận được biệt danh (sự kiện đăng ký chưa tới) thì tạm ẩn khỏi danh sách.
      if (player.nickname() != null) {
        response.addEntries(entry(i + 1, player, row.xp()).setIsMe(row.userId().equals(userId)));
      }
    }
    return response.setMe(me(userId, week.toString())).build();
  }

  public GetStatsResponse stats(String userId) {
    String week = weekOf(WeekSelector.WEEK_SELECTOR_CURRENT).toString();
    return GetStatsResponse.newBuilder()
        .setTotalXp(players.find(userId).orElse(UNKNOWN).totalXp())
        .setWeekXp(weekly.find(userId, week).map(Row::xp).orElse(0))
        .setWeekStart(week)
        .build();
  }

  private LeaderboardEntry.Builder me(String userId, String week) {
    Player player = players.find(userId).orElse(UNKNOWN);
    Optional<Row> row = weekly.find(userId, week).filter(r -> r.xp() > 0);
    int rank = row.map(r -> weekly.rankOf(r, week)).orElse(0);
    return entry(rank, player, row.map(Row::xp).orElse(0)).setIsMe(true);
  }

  private static LeaderboardEntry.Builder entry(int rank, Player player, int xp) {
    return LeaderboardEntry.newBuilder()
        .setRank(rank)
        .setNickname(player.nickname() == null ? "" : player.nickname())
        .setXp(xp)
        .setStreak(player.currentStreak());
  }

  private LocalDate weekOf(WeekSelector selector) {
    LocalDate current = VnTime.weekStart(VnTime.date(clock.millis()));
    return switch (selector) {
      case WEEK_SELECTOR_UNSPECIFIED, WEEK_SELECTOR_CURRENT -> current;
      case WEEK_SELECTOR_PREVIOUS -> current.minusWeeks(1);
      case UNRECOGNIZED -> throw AppException.invalid("week", FieldErrors.OUT_OF_RANGE);
    };
  }
}

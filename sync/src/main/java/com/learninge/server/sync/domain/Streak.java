package com.learninge.server.sync.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Chuỗi ngày học (BR-031): ngày học liên tiếp tính tới ngày học gần nhất. Tính lại từ toàn bộ nhật
 * ký đã gộp của mọi máy nên không phụ thuộc thứ tự đẩy.
 */
public record Streak(int current, int longest, LocalDate lastStudyDate) {
  public static final Streak NONE = new Streak(0, 0, null);

  /**
   * @param days các ngày học khác nhau, tăng dần
   * @param latestAllowed ngày xa nhất được tính (chặn máy để sai giờ ở tương lai)
   */
  public static Streak of(List<LocalDate> days, LocalDate latestAllowed) {
    int current = 0;
    int longest = 0;
    LocalDate last = null;
    for (LocalDate day : days) {
      if (day.isAfter(latestAllowed)) {
        break;
      }
      current = last != null && last.plusDays(1).equals(day) ? current + 1 : 1;
      longest = Math.max(longest, current);
      last = day;
    }
    return last == null ? NONE : new Streak(current, longest, last);
  }
}

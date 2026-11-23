package com.learninge.server.sync.service;

import com.learninge.proto.app.v1.Change;
import com.learninge.server.sync.store.XpEventTable.ScoredXp;

/** Kết quả xử lý một bản ghi trong lô. */
sealed interface Outcome {

  /** Đã ghi. {@code current} chỉ có với XP (để app biết số XP thật). */
  record Applied(long seq, Change current, ScoredXp xp) implements Outcome {
    static Applied at(long seq) {
      return new Applied(seq, null, null);
    }
  }

  /** Bản server mới hơn; app phải ghi đè bằng {@code current}. */
  record Stale(Change current) implements Outcome {}

  /** Bản ghi chỉ-thêm đã có. */
  record Duplicate(long seq, Change current) implements Outcome {}

  /** Không ghi. */
  record Rejected(Reason reason) implements Outcome {}

  /** Mã lỗi theo bản ghi (GRPC-API EP-11). */
  enum Reason {
    DECK_NOT_OWNED("Bộ từ không thuộc tài khoản này"),
    UNKNOWN_ENTITY("Loại dữ liệu chưa được hỗ trợ"),
    INVALID_RECORD("Dữ liệu không hợp lệ"),
    UNKNOWN_TEST("Không tìm thấy đề thi");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    String message() {
      return message;
    }
  }
}

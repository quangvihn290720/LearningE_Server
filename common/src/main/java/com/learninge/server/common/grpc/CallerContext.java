package com.learninge.server.common.grpc;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import io.grpc.Context;

/** Người dùng của lời gọi hiện tại. Chỉ interceptor đã xác thực mới được đặt giá trị. */
public final class CallerContext {
  public static final Context.Key<String> USER_ID = Context.key("learninge-user-id");

  private CallerContext() {}

  /** Không bao giờ lấy {@code user_id} từ message (chống IDOR — TM-14). */
  public static String userId() {
    String userId = USER_ID.get();
    if (userId == null) {
      throw AppException.of(ErrorCode.UNAUTHENTICATED);
    }
    return userId;
  }
}

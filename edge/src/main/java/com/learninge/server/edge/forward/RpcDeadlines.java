package com.learninge.server.edge.forward;

import com.learninge.proto.app.v1.AuthServiceGrpc;
import com.learninge.proto.app.v1.ContentServiceGrpc;
import com.learninge.proto.app.v1.LeaderboardServiceGrpc;
import com.learninge.proto.app.v1.SyncServiceGrpc;
import java.time.Duration;

/** Trần thời gian (GRPC-API §Deadline). */
public final class RpcDeadlines {
  static final Duration AUTH = Duration.ofSeconds(8);
  static final Duration PUSH = Duration.ofSeconds(18);
  static final Duration PULL = Duration.ofSeconds(13);
  static final Duration DEFAULT = Duration.ofSeconds(3);
  static final Duration OPTIONAL = Duration.ofMillis(300);

  /** Phần dành cho edge nhận kết quả và trả về app. */
  static final Duration MARGIN = Duration.ofMillis(100);

  private RpcDeadlines() {}

  /** Trần cho lời gọi vào edge = deadline app quy định + 2 giây. */
  public static Duration inboundCap(String fullMethodName) {
    if (fullMethodName.equals(SyncServiceGrpc.getPushMethod().getFullMethodName())) {
      return Duration.ofSeconds(22);
    }
    String service = fullMethodName.substring(0, fullMethodName.lastIndexOf('/'));
    return switch (service) {
      case AuthServiceGrpc.SERVICE_NAME -> Duration.ofSeconds(12);
      case SyncServiceGrpc.SERVICE_NAME, ContentServiceGrpc.SERVICE_NAME -> Duration.ofSeconds(17);
      case LeaderboardServiceGrpc.SERVICE_NAME -> Duration.ofSeconds(7);
      default -> Duration.ofSeconds(5);
    };
  }
}

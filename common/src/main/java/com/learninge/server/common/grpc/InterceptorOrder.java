package com.learninge.server.common.grpc;

/**
 * Thứ tự chạy của interceptor: số nhỏ chạy trước (ngoài cùng). Spring gRPC sắp theo {@code @Order}.
 *
 * <p>Khóa nội bộ / xóa metadata giả phải chạy trước mọi thứ đọc metadata (TM-33).
 */
public final class InterceptorOrder {
  public static final int TRUST_BOUNDARY = 100;
  public static final int REQUEST_ID = 200;
  public static final int CLIENT_VERSION = 300;
  public static final int DEADLINE_CAP = 400;
  public static final int IP_RATE_LIMIT = 500;
  public static final int AUTHENTICATION = 600;
  public static final int TOKEN_RATE_LIMIT = 700;
  public static final int CONCURRENCY = 800;
  public static final int ERROR_MAPPING = 1000;

  private InterceptorOrder() {}
}

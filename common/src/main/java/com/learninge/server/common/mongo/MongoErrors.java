package com.learninge.server.common.mongo;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.mongodb.MongoException;
import com.mongodb.MongoServerException;

/** Mongo ngừng/không kết nối được → {@code UNAVAILABLE}; lỗi do máy chủ Mongo trả về thì không. */
public final class MongoErrors {
  private static final int DUPLICATE_KEY = 11000;

  private MongoErrors() {}

  public static AppException translate(RuntimeException error) {
    boolean unreachable =
        error instanceof MongoException && !(error instanceof MongoServerException);
    return unreachable ? AppException.of(ErrorCode.SERVICE_UNAVAILABLE) : null;
  }

  public static boolean isDuplicateKey(MongoException error) {
    return error.getCode() == DUPLICATE_KEY;
  }
}

package com.learninge.server.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** Gom lỗi từng trường rồi ném một lần, để app báo đủ các ô sai (trailer {@code error-fields}). */
public final class FieldErrors {
  public static final String REQUIRED = "REQUIRED";
  public static final String TOO_SHORT = "TOO_SHORT";
  public static final String TOO_LONG = "TOO_LONG";
  public static final String INVALID_FORMAT = "INVALID_FORMAT";
  public static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private final Map<String, String> fields = new LinkedHashMap<>();

  public FieldErrors add(String field, String reason) {
    fields.putIfAbsent(field, reason);
    return this;
  }

  /** Kiểm độ dài theo số ký tự Unicode, không theo số {@code char}. */
  public FieldErrors length(String field, String value, int min, int max) {
    int length = value.codePointCount(0, value.length());
    if (length == 0 && min > 0) {
      add(field, REQUIRED);
    } else if (length < min) {
      add(field, TOO_SHORT);
    } else if (length > max) {
      add(field, TOO_LONG);
    }
    return this;
  }

  public FieldErrors check(boolean valid, String field, String reason) {
    if (!valid) {
      add(field, reason);
    }
    return this;
  }

  public void throwIfAny() {
    if (!fields.isEmpty()) {
      throw AppException.invalid(fields);
    }
  }
}

package com.learninge.server.auth.domain;

import com.learninge.server.common.error.ErrorCode;

/** Hai bộ đếm khóa tách biệt của một email. */
public enum LockScope {
  LOGIN(ErrorCode.INVALID_CREDENTIALS, ErrorCode.ACCOUNT_LOCKED),
  RESET(ErrorCode.INVALID_SECURITY_ANSWER, ErrorCode.RESET_LOCKED);

  private final ErrorCode wrong;
  private final ErrorCode locked;

  LockScope(ErrorCode wrong, ErrorCode locked) {
    this.wrong = wrong;
    this.locked = locked;
  }

  public ErrorCode wrong() {
    return wrong;
  }

  public ErrorCode locked() {
    return locked;
  }
}

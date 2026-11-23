package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.OpaqueToken;
import com.learninge.server.auth.store.UserRow;

/** Kết quả đăng ký/đăng nhập/đặt lại mật khẩu. */
public record Session(OpaqueToken token, long expiresAt, UserRow user) {}

package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.OpaqueToken;
import com.learninge.server.auth.store.TokenStore;
import com.learninge.server.auth.store.TokenStore.ActiveToken;
import com.learninge.server.auth.store.UserRow;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** Cấp, kiểm và thu hồi token phiên (BR-057, BR-058). */
public final class SessionService {
  private final TokenStore tokens;
  private final Duration ttl;
  private final Clock clock;

  public SessionService(TokenStore tokens, Duration ttl, Clock clock) {
    this.tokens = tokens;
    this.ttl = ttl;
    this.clock = clock;
  }

  /** Gọi trong giao dịch ghi. */
  Session issue(UserRow user, String deviceName) {
    OpaqueToken token = OpaqueToken.generate();
    long now = clock.millis();
    long expiresAt = now + ttl.toMillis();
    tokens.insert(user.id(), token.hash(), deviceName, now, expiresAt);
    return new Session(token, expiresAt, user);
  }

  /** Gọi trong giao dịch ghi: thu hồi mọi token cũ rồi cấp token mới cho máy đang gọi. */
  Session replaceAll(UserRow user, String deviceName) {
    tokens.revokeAll(user.id(), clock.millis());
    return issue(user, deviceName);
  }

  public Optional<ActiveToken> validate(byte[] tokenSha256) {
    return tokens.findActive(OpaqueToken.hashHex(tokenSha256), clock.millis());
  }

  public void revoke(byte[] tokenSha256) {
    tokens.revoke(OpaqueToken.hashHex(tokenSha256), clock.millis());
  }
}

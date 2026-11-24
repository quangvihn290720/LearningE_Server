package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.OpaqueToken;
import com.learninge.server.auth.store.TokenStore;
import com.learninge.server.auth.store.TokenStore.ActiveToken;
import com.learninge.server.auth.store.UserRow;
import com.learninge.server.auth.store.TokenStore.DeviceSession;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Cấp, kiểm, gia hạn và thu hồi token phiên (BR-057, BR-058).
 *
 * <p>Phiên còn dùng được tự gia hạn khi đã qua nửa hạn, tới tuổi tối đa thì thôi; phiên bỏ không 30
 * ngày vẫn hết hạn như cũ.
 */
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long TOUCH_EVERY_MS = Duration.ofDays(1).toMillis();

    private final TokenStore tokens;
    private final Duration ttl;
    private final Duration maxAge;
    private final Clock clock;

    public SessionService(
            TokenStore tokens, Duration ttl, Duration maxAge, Clock clock) {
        this.tokens = tokens;
        this.ttl = ttl;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * Gọi trong giao dịch ghi.
     */
    Session issue(UserRow user, String deviceName) {
        OpaqueToken token = OpaqueToken.generate();
        long now = clock.millis();
        long expiresAt = now + ttl.toMillis();
        tokens.insert(user.id(), token.hash(), deviceName, now, expiresAt);
        return new Session(token, expiresAt, user);
    }

    /**
     * Gọi trong giao dịch ghi: thu hồi mọi token cũ rồi cấp token mới cho máy đang gọi.
     */
    Session replaceAll(UserRow user, String deviceName) {
        tokens.revokeAll(user.id(), clock.millis());
        return issue(user, deviceName);
    }

    public Optional<ActiveToken> validate(byte[] tokenSha256) {
        long now = clock.millis();
        return tokens.findActive(OpaqueToken.hashHex(tokenSha256), now).map(t -> keepAlive(t, now));
    }

    /**
     * Ghi nhiều nhất một lần mỗi ngày cho mỗi phiên, vì edge hỏi lại rất thường.
     */
    private ActiveToken keepAlive(ActiveToken token, long now) {
        long renewed = Math.min(now + ttl.toMillis(), token.createdAt() + maxAge.toMillis());
        boolean pastHalf = token.expiresAt() - now < ttl.toMillis() / 2;
        long expiresAt = pastHalf ? Math.max(token.expiresAt(), renewed) : token.expiresAt();
        if (expiresAt == token.expiresAt() && now - token.lastUsedAt() < TOUCH_EVERY_MS) {
            return token;
        }
        try {
            tokens.touch(token.id(), now, expiresAt);
            return token.expiringAt(expiresAt);
        } catch (DataAccessException | TransactionException e) {
            // Gia hạn chỉ là tiện ích: kho bận thì lần gọi sau làm, không chặn người dùng.
            log.warn("Chưa gia hạn được phiên: {}", e.getClass().getSimpleName());
            return token;
        }
    }

    /**
     * Dùng gần nhất trước.
     */
    public List<DeviceSession> list(String userId) {
        return tokens.activeOf(userId, clock.millis());
    }

    public void revokeOne(String userId, long sessionId) {
        if (tokens.revokeOne(userId, sessionId, clock.millis()) == 0) {
            throw AppException.of(ErrorCode.NOT_FOUND);
        }
    }

    public int revokeOthers(String userId, byte[] currentSha256) {
        String current = OpaqueToken.hashHex(currentSha256);
        return tokens.revokeOthers(userId, current, clock.millis());
    }

    public void revoke(byte[] tokenSha256) {
        tokens.revoke(OpaqueToken.hashHex(tokenSha256), clock.millis());
    }
}

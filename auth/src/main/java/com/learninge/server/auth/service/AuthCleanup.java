package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.EmailKeys;
import com.learninge.server.auth.store.AttemptStore;
import com.learninge.server.auth.store.TokenStore;
import com.learninge.server.common.sqlite.SqliteDatabase;

import java.time.Clock;
import java.time.Duration;

/**
 * Dọn dữ liệu hết hạn (DATABASE-DESIGN §Retention).
 */
public final class AuthCleanup {
    private static final Duration TOKEN_RETENTION = Duration.ofDays(7);
    private static final Duration ATTEMPT_RETENTION = Duration.ofHours(24);

    private final SqliteDatabase database;
    private final TokenStore tokens;
    private final AttemptStore attempts;
    private final EmailKeys keys;
    private final Clock clock;

    public AuthCleanup(
            SqliteDatabase database,
            TokenStore tokens,
            AttemptStore attempts,
            EmailKeys keys,
            Clock clock) {
        this.database = database;
        this.tokens = tokens;
        this.attempts = attempts;
        this.keys = keys;
        this.clock = clock;
    }

    public void deleteExpired() {
        long now = clock.millis();
        tokens.deleteEndedBefore(now - TOKEN_RETENTION.toMillis());
        attempts.deleteInactiveBefore(now - ATTEMPT_RETENTION.toMillis());
    }

    /**
     * Đổi khóa server thì các {@code subject} cũ vô nghĩa.
     */
    public void clearAttemptsWhenKeyChanged() {
        database.inWriteTx(
                () -> {
                    String current = keys.fingerprint();
                    if (!attempts.keyFingerprint().orElse("").equals(current)) {
                        attempts.deleteAll();
                        attempts.saveKeyFingerprint(current);
                    }
                });
    }
}

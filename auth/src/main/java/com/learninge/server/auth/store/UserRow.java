package com.learninge.server.auth.store;

import com.learninge.server.auth.domain.LockState;
import com.learninge.server.auth.domain.PasswordHasher.Hash;

/**
 * Một dòng {@code users} kèm câu hỏi bảo mật.
 */
public record UserRow(
        long id,
        String uuid,
        String email,
        String nickname,
        String displayName,
        Hash password,
        int questionId,
        Hash answer,
        LockState loginLock,
        LockState resetLock,
        long createdAt,
        long updatedAt) {
}

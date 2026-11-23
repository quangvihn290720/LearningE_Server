package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.AccountRules;
import com.learninge.server.auth.domain.EmailKeys;
import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.auth.domain.LockState;
import com.learninge.server.auth.domain.PasswordHasher;
import com.learninge.server.auth.store.UserStore;
import com.learninge.server.common.error.FieldErrors;

/**
 * EP-08, EP-09: quên mật khẩu bằng câu hỏi bảo mật.
 */
public final class ResetService {
    private final CredentialGuard guard;
    private final SessionService sessions;
    private final UserStore users;
    private final EmailKeys keys;
    private final PasswordHasher hasher;

    public ResetService(
            CredentialGuard guard,
            SessionService sessions,
            UserStore users,
            EmailKeys keys,
            PasswordHasher hasher) {
        this.guard = guard;
        this.sessions = sessions;
        this.users = users;
        this.keys = keys;
        this.hasher = hasher;
    }

    /**
     * Email không tồn tại vẫn nhận một câu hỏi ổn định, nên không dò được email nào có thật.
     */
    public int questionFor(String rawEmail) {
        String email = AccountRules.normalizeEmail(rawEmail);
        FieldErrors errors = new FieldErrors();
        AccountRules.checkEmail(errors, email);
        errors.throwIfAny();
        int fake = keys.fakeQuestionId(email);
        return users.findByEmail(email).map(u -> u.questionId()).orElse(fake);
    }

    public Session reset(String rawEmail, String answer, String newPassword, String deviceName) {
        String email = AccountRules.normalizeEmail(rawEmail);
        FieldErrors errors = new FieldErrors();
        AccountRules.checkEmail(errors, email);
        AccountRules.checkAnswer(errors, answer);
        AccountRules.checkPassword(errors, "new_password", newPassword);
        AccountRules.checkDeviceName(errors, deviceName);
        errors.throwIfAny();
        return guard.check(
                LockScope.RESET,
                email,
                AccountRules.normalizeAnswer(answer),
                () -> hasher.hash(newPassword),
                (user, password) -> {
                    users.updatePassword(user.id(), password);
                    users.saveLock(user.id(), LockScope.LOGIN, LockState.CLEAR);
                    return sessions.replaceAll(user, deviceName);
                });
    }
}

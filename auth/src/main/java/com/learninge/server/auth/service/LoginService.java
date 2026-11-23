package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.AccountRules;
import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.common.error.FieldErrors;

/**
 * EP-03.
 */
public final class LoginService {
    private final CredentialGuard guard;
    private final SessionService sessions;

    public LoginService(CredentialGuard guard, SessionService sessions) {
        this.guard = guard;
        this.sessions = sessions;
    }

    public Session login(String rawEmail, String password, String deviceName) {
        String email = AccountRules.normalizeEmail(rawEmail);
        FieldErrors errors = new FieldErrors();
        AccountRules.checkEmail(errors, email);
        errors.length("password", password, 1, 64);
        AccountRules.checkDeviceName(errors, deviceName);
        errors.throwIfAny();
        return guard.check(
                LockScope.LOGIN,
                email,
                password,
                () -> null,
                (user, nothing) -> sessions.issue(user, deviceName));
    }
}

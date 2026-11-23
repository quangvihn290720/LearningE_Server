package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.EmailKeys;
import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.auth.domain.LockState;
import com.learninge.server.auth.domain.PasswordHasher;
import com.learninge.server.auth.domain.PasswordHasher.Hash;
import com.learninge.server.auth.store.AttemptStore;
import com.learninge.server.auth.store.UserRow;
import com.learninge.server.auth.store.UserStore;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.sqlite.SqliteDatabase;

import java.time.Clock;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Kiểm bí mật (mật khẩu hoặc câu trả lời) với khóa tăng dần. Email có thật và không có thật đi cùng
 * một đường: cùng phép băm, cùng bộ đếm, cùng lỗi trả về (BR-076).
 *
 * <p>Thứ tự: đang khóa → từ chối ngay, không băm. Băm ngoài giao dịch. Rồi một giao dịch ngắn đọc
 * lại trạng thái khóa và ghi kết quả, nên hai yêu cầu song song không bỏ sót lần sai.
 */
public final class CredentialGuard {
    private final SqliteDatabase database;
    private final UserStore users;
    private final AttemptStore attempts;
    private final EmailKeys keys;
    private final PasswordHasher hasher;
    private final AuthEvents events;
    private final Clock clock;

    public CredentialGuard(
            SqliteDatabase database,
            UserStore users,
            AttemptStore attempts,
            EmailKeys keys,
            PasswordHasher hasher,
            AuthEvents events,
            Clock clock) {
        this.database = database;
        this.users = users;
        this.attempts = attempts;
        this.keys = keys;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param prepare   việc nặng (vd băm mật khẩu mới), chạy ngoài giao dịch và chỉ khi bí mật đúng
     * @param onSuccess chạy trong giao dịch, sau khi bộ đếm của {@code scope} về 0
     */
    <P, T> T check(
            LockScope scope,
            String email,
            String secret,
            Supplier<P> prepare,
            BiFunction<UserRow, P, T> onSuccess) {
        Optional<UserRow> candidate = users.findByEmail(email);
        rejectIfLocked(
                scope, candidate.map(u -> lockOf(u, scope)).orElseGet(() -> unknown(scope, email)));

        Hash expected = candidate.map(u -> secretOf(u, scope)).orElseGet(hasher::decoy);
        boolean matches = hasher.verify(secret, expected) && candidate.isPresent();
        P prepared = matches ? prepare.get() : null;

        // Trả lỗi ra ngoài lambda rồi mới ném: ném bên trong sẽ rollback cả bộ đếm vừa ghi.
        Result<T> result =
                database.inWriteTx(
                        () -> finish(scope, email, expected, matches, u -> onSuccess.apply(u, prepared)));
        if (result.error() != null) {
            throw result.error();
        }
        return result.value();
    }

    private record Result<T>(T value, AppException error) {
    }

    private <T> Result<T> finish(
            LockScope scope,
            String email,
            Hash verified,
            boolean matches,
            Function<UserRow, T> onSuccess) {
        long now = clock.millis();
        Optional<UserRow> user = users.lockByEmail(email);
        String subject = keys.attemptSubject(email);
        LockState state =
                user.map(u -> lockOf(u, scope)).orElseGet(() -> attempts.lock(subject, scope)).at(now);
        if (state.isLocked(now)) {
            return new Result<>(null, AppException.retryAfter(scope.locked(), state.secondsLeft(now)));
        }
        // Bí mật vừa bị đổi ở máy khác trong lúc băm thì kết quả cũ không còn giá trị.
        boolean stillCurrent = user.isPresent() && secretOf(user.get(), scope).equals(verified);
        if (matches && stillCurrent) {
            users.saveLock(user.get().id(), scope, LockState.CLEAR);
            return new Result<>(onSuccess.apply(user.get()), null);
        }
        LockState next = state.afterFailure(now);
        user.ifPresentOrElse(
                u -> users.saveLock(u.id(), scope, next), () -> attempts.save(subject, scope, next));
        events.failed(scope, user);
        if (!next.isLocked(now)) {
            return new Result<>(null, AppException.of(scope.wrong()));
        }
        events.locked(scope, next, now, user);
        return new Result<>(null, AppException.retryAfter(scope.locked(), next.secondsLeft(now)));
    }

    private void rejectIfLocked(LockScope scope, LockState state) {
        long now = clock.millis();
        if (state.at(now).isLocked(now)) {
            throw AppException.retryAfter(scope.locked(), state.secondsLeft(now));
        }
    }

    private LockState unknown(LockScope scope, String email) {
        return attempts.find(keys.attemptSubject(email), scope);
    }

    private static LockState lockOf(UserRow user, LockScope scope) {
        return scope == LockScope.LOGIN ? user.loginLock() : user.resetLock();
    }

    private static Hash secretOf(UserRow user, LockScope scope) {
        return scope == LockScope.LOGIN ? user.password() : user.answer();
    }
}

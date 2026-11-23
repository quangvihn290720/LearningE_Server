package com.learninge.server.auth.service;

import com.learninge.server.auth.domain.AccountRules;
import com.learninge.server.auth.domain.EmailKeys;
import com.learninge.server.auth.domain.PasswordHasher;
import com.learninge.server.auth.domain.PasswordHasher.Hash;
import com.learninge.server.auth.store.AttemptStore;
import com.learninge.server.auth.store.UserRow;
import com.learninge.server.auth.store.UserStore;
import com.learninge.server.auth.store.UserStore.NewUser;
import com.learninge.server.auth.store.UserStore.PublicProfile;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.security.SlidingWindowLimiter;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.sqlite.SqliteErrors;
import io.grpc.Status;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

/** EP-02, EP-05 (hồ sơ), EP-06, EP-07, EP-10 và xuất hồ sơ công khai. */
public final class AccountService {
  /** Dữ liệu đăng ký thô từ app. */
  public record Registration(
      String email,
      String password,
      String nickname,
      String displayName,
      int questionId,
      String answer,
      String deviceName) {}

  private final SqliteDatabase database;
  private final UserStore users;
  private final AttemptStore attempts;
  private final SessionService sessions;
  private final EmailKeys keys;
  private final PasswordHasher hasher;
  private final AuthEvents events;
  private final SlidingWindowLimiter wrongPasswords;
  private final Clock clock;

  public AccountService(
      SqliteDatabase database,
      UserStore users,
      AttemptStore attempts,
      SessionService sessions,
      EmailKeys keys,
      PasswordHasher hasher,
      AuthEvents events,
      SlidingWindowLimiter wrongPasswords,
      Clock clock) {
    this.database = database;
    this.users = users;
    this.attempts = attempts;
    this.sessions = sessions;
    this.keys = keys;
    this.hasher = hasher;
    this.events = events;
    this.wrongPasswords = wrongPasswords;
    this.clock = clock;
  }

  public Session register(Registration raw) {
    String email = AccountRules.normalizeEmail(raw.email());
    String displayName = raw.displayName().strip();
    FieldErrors errors = new FieldErrors();
    AccountRules.checkEmail(errors, email);
    AccountRules.checkPassword(errors, "password", raw.password());
    AccountRules.checkNickname(errors, raw.nickname());
    AccountRules.checkDisplayName(errors, displayName);
    errors.check(
        raw.questionId() >= 1 && raw.questionId() <= 5,
        "security_question_id",
        FieldErrors.OUT_OF_RANGE);
    AccountRules.checkAnswer(errors, raw.answer());
    AccountRules.checkDeviceName(errors, raw.deviceName());
    errors.throwIfAny();

    Hash password = hasher.hash(raw.password());
    Hash answer = hasher.hash(AccountRules.normalizeAnswer(raw.answer()));
    NewUser user =
        new NewUser(
            UUID.randomUUID().toString(),
            email,
            raw.nickname(),
            displayName,
            password,
            raw.questionId(),
            answer);
    try {
      return database.inWriteTx(
          () -> {
            users.insert(user, clock.millis());
            attempts.delete(keys.attemptSubject(email));
            UserRow row = users.lockByUuid(user.uuid()).orElseThrow();
            events.registered(row);
            return sessions.issue(row, raw.deviceName());
          });
    } catch (DataAccessException e) {
      throw duplicate(e);
    }
  }

  public UserRow profile(String userId) {
    return users.findByUuid(userId).orElseThrow(() -> AppException.of(ErrorCode.UNAUTHENTICATED));
  }

  /** Trường rỗng ({@code Optional.empty}) nghĩa là không đổi. */
  public UserRow updateProfile(
      String userId, Optional<String> nickname, Optional<String> displayName) {
    Optional<String> name = displayName.map(String::strip);
    FieldErrors errors = new FieldErrors();
    nickname.ifPresent(v -> AccountRules.checkNickname(errors, v));
    name.ifPresent(v -> AccountRules.checkDisplayName(errors, v));
    errors.throwIfAny();
    try {
      return database.inWriteTx(
          () -> {
            UserRow current = existing(userId);
            String newNickname = nickname.orElse(current.nickname());
            // Phiên bản hồ sơ phải luôn tăng để bên nhận bỏ được sự kiện cũ đến muộn.
            long version = Math.max(clock.millis(), current.updatedAt() + 1);
            users.updateProfile(
                current.id(), newNickname, name.orElse(current.displayName()), version);
            events.profileChanged(userId, newNickname, version);
            return users.lockByUuid(userId).orElseThrow();
          });
    } catch (DataAccessException e) {
      throw duplicate(e);
    }
  }

  public Session changePassword(
      String userId, String currentPassword, String newPassword, String deviceName) {
    FieldErrors errors = new FieldErrors();
    errors.length("current_password", currentPassword, 1, 64);
    AccountRules.checkPassword(errors, "new_password", newPassword);
    AccountRules.checkDeviceName(errors, deviceName);
    errors.throwIfAny();
    Hash verified = verifyCurrent(userId, currentPassword);
    if (newPassword.equals(currentPassword)) {
      throw AppException.invalid("new_password", FieldErrors.INVALID_FORMAT);
    }
    Hash replacement = hasher.hash(newPassword);
    return database.inWriteTx(
        () -> {
          UserRow user = unchanged(userId, verified);
          users.updatePassword(user.id(), replacement);
          return sessions.replaceAll(user, deviceName);
        });
  }

  public void deleteAccount(String userId, String password) {
    FieldErrors errors = new FieldErrors();
    errors.length("password", password, 1, 64);
    errors.throwIfAny();
    Hash verified = verifyCurrent(userId, password);
    database.inWriteTx(
        () -> {
          UserRow user = unchanged(userId, verified);
          users.delete(user.id());
          events.deleted(userId);
        });
  }

  public List<PublicProfile> exportProfiles(long afterId, int limit) {
    return users.exportAfter(afterId, limit);
  }

  /** Sai quá 5 lần trong 15 phút thì chặn trước khi băm (EP-07, EP-10). */
  private Hash verifyCurrent(String userId, String password) {
    long wait = wrongPasswords.retryAfterSeconds(userId);
    if (wait > 0) {
      throw AppException.retryAfter(ErrorCode.RATE_LIMITED, wait);
    }
    Hash expected = profile(userId).password();
    if (hasher.verify(password, expected)) {
      wrongPasswords.reset(userId);
      return expected;
    }
    wrongPasswords.tryAcquire(userId);
    wait = wrongPasswords.retryAfterSeconds(userId);
    if (wait > 0) {
      throw AppException.retryAfter(ErrorCode.RATE_LIMITED, wait);
    }
    throw AppException.of(
        ErrorCode.INVALID_CREDENTIALS,
        Status.Code.PERMISSION_DENIED,
        "Mật khẩu hiện tại không đúng");
  }

  private UserRow existing(String userId) {
    return users.lockByUuid(userId).orElseThrow(() -> AppException.of(ErrorCode.UNAUTHENTICATED));
  }

  private UserRow unchanged(String userId, Hash verified) {
    UserRow user = existing(userId);
    if (!user.password().equals(verified)) {
      throw AppException.of(ErrorCode.UNAUTHENTICATED);
    }
    return user;
  }

  private static RuntimeException duplicate(DataAccessException error) {
    if (SqliteErrors.isUniqueViolation(error, "users.email")) {
      return AppException.of(ErrorCode.EMAIL_TAKEN);
    }
    if (SqliteErrors.isUniqueViolation(error, "users.nickname")) {
      return AppException.of(ErrorCode.NICKNAME_TAKEN);
    }
    return error;
  }
}

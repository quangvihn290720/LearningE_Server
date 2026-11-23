package com.learninge.server.auth;

import com.learninge.server.auth.domain.EmailKeys;
import com.learninge.server.auth.domain.PasswordHasher;
import com.learninge.server.auth.grpc.AuthBackendService;
import com.learninge.server.auth.grpc.TokenValidatorService;
import com.learninge.server.auth.service.AccountService;
import com.learninge.server.auth.service.AuthCleanup;
import com.learninge.server.auth.service.AuthEvents;
import com.learninge.server.auth.service.CredentialGuard;
import com.learninge.server.auth.service.LoginService;
import com.learninge.server.auth.service.ResetService;
import com.learninge.server.auth.service.SessionService;
import com.learninge.server.auth.store.AttemptStore;
import com.learninge.server.auth.store.TokenStore;
import com.learninge.server.auth.store.UserStore;
import com.learninge.server.common.config.InternalServerConfiguration;
import com.learninge.server.common.config.LearningeProperties;
import com.learninge.server.common.config.SecretKeys;
import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.ops.BackgroundTasks;
import com.learninge.server.common.outbox.OutboxConfiguration;
import com.learninge.server.common.outbox.OutboxConfiguration.EventSource;
import com.learninge.server.common.outbox.OutboxWriter;
import com.learninge.server.common.security.SlidingWindowLimiter;
import com.learninge.server.common.security.UserPseudonym;
import com.learninge.server.common.sqlite.SqliteBackupCommand;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.sqlite.SqliteErrors;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Toàn bộ dây nối của dịch vụ auth, đọc từ trên xuống: kho → quy tắc → nghiệp vụ → gRPC. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
@Import({InternalServerConfiguration.class, OutboxConfiguration.class})
public class AuthServiceConfig {

  @Bean
  SqliteDatabase database(AuthProperties properties) {
    return SqliteDatabase.open(properties.dbPath(), "db/migration/auth");
  }

  @Bean
  ErrorMappingInterceptor.Translator sqliteErrorTranslator() {
    return SqliteErrors::translate;
  }

  @Bean
  EventSource eventSource() {
    return new EventSource(AuthApplication.NAME);
  }

  @Bean
  SqliteBackupCommand backupCommand(SqliteDatabase database, Clock clock) {
    return new SqliteBackupCommand(database, AuthApplication.NAME, clock);
  }

  @Bean
  UserStore userStore(SqliteDatabase database) {
    return new UserStore(database);
  }

  @Bean
  TokenStore tokenStore(SqliteDatabase database) {
    return new TokenStore(database);
  }

  @Bean
  AttemptStore attemptStore(SqliteDatabase database) {
    return new AttemptStore(database);
  }

  @Bean
  PasswordHasher passwordHasher(AuthProperties properties) {
    return new PasswordHasher(properties.maxConcurrentHashes());
  }

  @Bean
  EmailKeys emailKeys(AuthProperties properties, LearningeProperties common) {
    return new EmailKeys(
        SecretKeys.hmacKey("LEARNINGE_SERVER_KEY", properties.serverKey(), common.env()));
  }

  @Bean
  UserPseudonym userPseudonym(AuthProperties properties, LearningeProperties common) {
    return new UserPseudonym(
        SecretKeys.hmacKey("LEARNINGE_PSEUDONYM_KEY", properties.pseudonymKey(), common.env()));
  }

  @Bean
  AuthEvents authEvents(OutboxWriter outbox, UserPseudonym pseudonym) {
    return new AuthEvents(outbox, pseudonym);
  }

  @Bean
  SessionService sessionService(TokenStore tokens, AuthProperties properties, Clock clock) {
    return new SessionService(tokens, properties.tokenTtl(), clock);
  }

  @Bean
  CredentialGuard credentialGuard(
      SqliteDatabase database,
      UserStore users,
      AttemptStore attempts,
      EmailKeys keys,
      PasswordHasher hasher,
      AuthEvents events,
      Clock clock) {
    return new CredentialGuard(database, users, attempts, keys, hasher, events, clock);
  }

  @Bean
  LoginService loginService(CredentialGuard guard, SessionService sessions) {
    return new LoginService(guard, sessions);
  }

  @Bean
  ResetService resetService(
      CredentialGuard guard,
      SessionService sessions,
      UserStore users,
      EmailKeys keys,
      PasswordHasher hasher) {
    return new ResetService(guard, sessions, users, keys, hasher);
  }

  @Bean
  AccountService accountService(
      SqliteDatabase database,
      UserStore users,
      AttemptStore attempts,
      SessionService sessions,
      EmailKeys keys,
      PasswordHasher hasher,
      AuthEvents events,
      Clock clock) {
    SlidingWindowLimiter wrongPasswords =
        new SlidingWindowLimiter(5, Duration.ofMinutes(15), clock);
    return new AccountService(
        database, users, attempts, sessions, keys, hasher, events, wrongPasswords, clock);
  }

  @Bean
  AuthCleanup authCleanup(
      SqliteDatabase database,
      TokenStore tokens,
      AttemptStore attempts,
      EmailKeys keys,
      BackgroundTasks tasks,
      Clock clock) {
    AuthCleanup cleanup = new AuthCleanup(database, tokens, attempts, keys, clock);
    cleanup.clearAttemptsWhenKeyChanged();
    tasks.every("auth-cleanup", Duration.ofHours(1), cleanup::deleteExpired);
    return cleanup;
  }

  @Bean
  AuthBackendService authBackendService(
      AccountService accounts, LoginService login, ResetService reset, SessionService sessions) {
    return new AuthBackendService(accounts, login, reset, sessions);
  }

  @Bean
  TokenValidatorService tokenValidatorService(SessionService sessions, UserPseudonym pseudonym) {
    return new TokenValidatorService(sessions, pseudonym);
  }
}

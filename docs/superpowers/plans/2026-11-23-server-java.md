# LearningE Server (Java) Implementation Plan

> **For agentic workers:** execute natively with superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** six gRPC services in Java 21 / Spring Boot 4.1.2 implementing EP-01..EP-16, runnable as containers or one JVM.

**Architecture:** `edge` is the only public entry; `auth`/`sync` own SQLite and emit events through a transactional
outbox; `content`/`gamification`/`insight` own MongoDB databases. Contracts live in `proto`, shared plumbing in `common`.

**Tech Stack:** Gradle 9.6.0, Spring Boot 4.1.2 BOM (Spring gRPC, grpc-java, protobuf), sqlite-jdbc, Flyway, Spring Data MongoDB, Caffeine, JUnit, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-23-server-java-design.md` plus `LearningE_app/docs/engineering/*`.

## Global Constraints
- Java 21; versions only in `gradle/libs.versions.toml`; BOM libraries carry no version.
- No Lombok, JPA, MVC/Tomcat, JWT, streaming RPCs, string-built SQL, or logging of messages/tokens/emails.
- `user_id` comes only from `x-user-id` after `x-internal-auth` passed; secrets compared with `MessageDigest.isEqual`.
- Every error produced by the server carries trailer `error-code`; messages are short Vietnamese.
- Every state change that others must see writes its outbox rows in the same SQLite transaction.

## Review Focus
1. Two devices push the same append-only batch concurrently → one APPLIED, one DUPLICATE, XP counted once (Task 4).
2. Account deleted while a Push is in flight → `ACCOUNT_DELETED`, nothing resurrected, even if `user.registered` arrives late (Task 4).
3. Two parallel wrong logins at the lock threshold → exactly one lock level gained (Task 3).
4. Destination down then up → outbox drains, no duplicate effect, other destinations unaffected (Tasks 2, 5).
5. Token revoked through one device → cached token on edge is rejected at once (Task 6).

## Tasks
### Task 1: Build skeleton and contracts
`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `proto/**`.
- [ ] Protos copied verbatim from GRPC-API; internal package gets `java_package = com.learninge.proto.internal.v1`.
- [ ] `./gradlew :proto:build` generates messages and stubs.

### Task 2: `common`
Produces: `ErrorCode`, `AppException`, `GrpcErrors`, `InternalAuthServerInterceptor`, `RequestIdServerInterceptor`,
`DeadlineCapInterceptor`, `CallerContext.userId()`, `InternalClientInterceptor`, `Secret`, `SecretKeys`, `UuidV5.notebook(userId)`,
`UuidV7.next(Clock)`, `UserPseudonym.of(userId)`, `SqliteDatabase` (writer/reader `JdbcClient`, `inWriteTx`, `inReadTx`),
`OutboxWriter.append(...)`, `OutboxRelay`, `EventRouting`, `EventPolicy`, `OpsCommandRunner`.
- [ ] Tests: RFC 9562 vectors for v5/v7; backoff 2,4,8,15,15 s ±20 %; relay (ack, transient, poison→dead after 5, recovery, isolation);
  event policy; interceptor order (bad key → handler never runs).

### Task 3: `auth` (EP-02..EP-10, ValidateToken, ExportProfiles)
- [ ] Domain tests: PBKDF2 RFC 7914 vector, `LockPolicy` 60..3600 s, normalisers, field validation, fake question id.
- [ ] gRPC tests over a real SQLite file: TC-SRV-01..14.

### Task 4: `sync` (EP-11, EP-12, streak, exports, EventSink)
- [ ] Domain tests: XP rules and bounds, streak, VN week. gRPC tests TC-SRV-15..30.

### Task 5: `gamification`, `content`, `insight`
- [ ] Testcontainers Mongo: TC-SRV-31..45.

### Task 6: `edge`
- [ ] In-process fake backends: TC-SRV-46..52, TC-RPC-23..25, TC-SVC-03, 07, 10, 12.

### Task 7: `all-in-one`, compose, CI, docs
- [ ] `AllInOneTest` runs Register → Push → Pull → GetWeekly → DeleteAccount through `edge`.
- [ ] `docker compose up -d --wait` healthy; only `127.0.0.1:50051` published.

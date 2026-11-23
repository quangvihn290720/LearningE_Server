package com.learninge.server.auth.service;

import com.learninge.proto.internal.v1.AuthLocked;
import com.learninge.proto.internal.v1.AuthLoginFailed;
import com.learninge.proto.internal.v1.AuthScope;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.proto.internal.v1.UserProfileChanged;
import com.learninge.proto.internal.v1.UserRegistered;
import com.learninge.server.auth.domain.LockScope;
import com.learninge.server.auth.domain.LockState;
import com.learninge.server.auth.store.UserRow;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventType;
import com.learninge.server.common.outbox.OutboxWriter;
import com.learninge.server.common.security.UserPseudonym;

import java.util.Optional;

/**
 * Sự kiện của auth, payload cắt theo đích: insight chỉ thấy bút danh, không bao giờ có email.
 */
public final class AuthEvents {
    private final OutboxWriter outbox;
    private final UserPseudonym pseudonym;

    public AuthEvents(OutboxWriter outbox, UserPseudonym pseudonym) {
        this.outbox = outbox;
        this.pseudonym = pseudonym;
    }

    void registered(UserRow user) {
        outbox.append(
                EventType.USER_REGISTERED,
                user.uuid(),
                (destination, event) -> {
                    UserRegistered.Builder payload = UserRegistered.newBuilder();
                    switch (destination) {
                        case SYNC -> payload.setUserId(user.uuid());
                        case GAMIFICATION -> payload
                                .setUserId(user.uuid())
                                .setNickname(user.nickname())
                                .setCreatedAtMs(user.createdAt());
                        case INSIGHT -> payload.setUserHmac(pseudonym.of(user.uuid()));
                    }
                    event.setUserRegistered(payload);
                });
    }

    void profileChanged(String uuid, String nickname, long version) {
        outbox.append(
                EventType.USER_PROFILE_CHANGED,
                uuid,
                (destination, event) -> {
                    UserProfileChanged.Builder payload = UserProfileChanged.newBuilder();
                    if (destination == Destination.INSIGHT) {
                        payload.setUserHmac(pseudonym.of(uuid));
                    } else {
                        payload.setUserId(uuid).setNickname(nickname).setVersion(version);
                    }
                    event.setUserProfileChanged(payload);
                });
    }

    void deleted(String uuid) {
        outbox.append(
                EventType.USER_DELETED,
                uuid,
                (destination, event) -> {
                    UserDeleted.Builder payload = UserDeleted.newBuilder();
                    if (destination == Destination.INSIGHT) {
                        payload.setUserHmac(pseudonym.of(uuid));
                    } else {
                        payload.setUserId(uuid);
                    }
                    event.setUserDeleted(payload);
                });
    }

    void failed(LockScope scope, Optional<UserRow> user) {
        outbox.append(
                EventType.AUTH_LOGIN_FAILED,
                scope.name(),
                (destination, event) ->
                        event.setAuthLoginFailed(
                                AuthLoginFailed.newBuilder().setScope(proto(scope)).setUserHmac(hmac(user))));
    }

    void locked(LockScope scope, LockState state, long now, Optional<UserRow> user) {
        outbox.append(
                EventType.AUTH_LOCKED,
                scope.name(),
                (destination, event) ->
                        event.setAuthLocked(
                                AuthLocked.newBuilder()
                                        .setScope(proto(scope))
                                        .setLockLevel(state.lockCount())
                                        .setLockSeconds((int) state.secondsLeft(now))
                                        .setUserHmac(hmac(user))));
    }

    private String hmac(Optional<UserRow> user) {
        return user.map(u -> pseudonym.of(u.uuid())).orElse("");
    }

    private static AuthScope proto(LockScope scope) {
        return switch (scope) {
            case LOGIN -> AuthScope.AUTH_SCOPE_LOGIN;
            case RESET -> AuthScope.AUTH_SCOPE_RESET;
        };
    }
}

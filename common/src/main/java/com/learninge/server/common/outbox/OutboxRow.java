package com.learninge.server.common.outbox;

import com.google.protobuf.InvalidProtocolBufferException;
import com.learninge.proto.internal.v1.Event;

import java.util.Optional;

/**
 * Một dòng chờ phát.
 */
record OutboxRow(long id, String eventId, byte[] payload, int attempts, long nextAttemptAt) {

    /**
     * Rỗng khi payload hỏng (poison).
     */
    Optional<Event> event() {
        try {
            return Optional.of(Event.parseFrom(payload));
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
    }
}

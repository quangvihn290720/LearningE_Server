package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.grpc.CallInfo;
import com.learninge.server.common.id.UuidV7;

import java.time.Clock;
import java.util.function.BiConsumer;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ghi sự kiện vào outbox <b>trong cùng giao dịch</b> với thay đổi nghiệp vụ: một dòng cho mỗi đích,
 * cùng {@code event_id}, payload cắt theo đích. Commit xong thì đánh thức relay.
 */
public final class OutboxWriter {
    private final OutboxStore store;
    private final Clock clock;
    private final Runnable wakeRelay;

    public OutboxWriter(OutboxStore store, Clock clock, Runnable wakeRelay) {
        this.store = store;
        this.clock = clock;
        this.wakeRelay = wakeRelay;
    }

    public void append(
            EventType type, String aggregateId, BiConsumer<Destination, Event.Builder> payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Outbox phải được ghi trong giao dịch");
        }
        String eventId = UuidV7.next(clock).toString();
        long now = clock.millis();
        CallInfo info = CallInfo.KEY.get();
        for (Destination destination : type.destinations()) {
            Event.Builder event =
                    Event.newBuilder()
                            .setEventId(eventId)
                            .setType(type.id())
                            .setAggregate(type.aggregate())
                            .setAggregateId(destination == Destination.INSIGHT ? "" : aggregateId)
                            .setCreatedAtMs(now)
                            .setSource(type.source());
            if (info != null) {
                event.setRequestId(info.requestId());
            }
            payload.accept(destination, event);
            store.insert(eventId, destination, type, aggregateId, event.build().toByteArray(), now);
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        wakeRelay.run();
                    }
                });
    }
}

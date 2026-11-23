package com.learninge.server.common.outbox;

import com.learninge.server.common.ops.OpsCommand;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=outbox-requeue --event-id=<id>}: cho dòng "chết" chạy lại sau khi sửa lỗi.
 */
final class OutboxRequeueCommand implements OpsCommand {
    private final OutboxStore store;
    private final Clock clock;

    OutboxRequeueCommand(OutboxStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "outbox-requeue";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String eventId = OpsCommand.required(arguments, "event-id");
        if (store.requeue(eventId, clock.millis()) == 0) {
            throw new IllegalArgumentException("Không có dòng outbox chưa phát với event_id này");
        }
    }
}

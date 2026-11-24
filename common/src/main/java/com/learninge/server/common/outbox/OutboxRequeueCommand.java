package com.learninge.server.common.outbox;

import com.learninge.server.common.ops.OpsCommand;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=outbox-requeue --event-id=<id>} hoặc {@code --all-dead}: cho dòng "chết" chạy lại sau
 * khi sửa lỗi.
 */
final class OutboxRequeueCommand implements OpsCommand {
    private static final Logger log = LoggerFactory.getLogger(OutboxRequeueCommand.class);

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
        if (arguments.containsOption("all-dead")) {
            log.info("Cho chạy lại {} dòng", store.requeueAllDead(clock.millis()));
            return;
        }
        String eventId = OpsCommand.required(arguments, "event-id");
        if (store.requeue(eventId, clock.millis()) == 0) {
            throw new IllegalArgumentException("Không có dòng outbox chưa phát với event_id này");
        }
    }
}

package com.learninge.server.common.outbox;

import com.learninge.server.common.ops.OpsCommand;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=outbox-replay --destination=sync}: phát lại các sự kiện còn lưu (7 ngày) cho một đích
 * vừa khôi phục, để tài khoản tạo hoặc xóa sau bản sao lưu được áp lại.
 */
final class OutboxReplayCommand implements OpsCommand {
    private static final Logger log = LoggerFactory.getLogger(OutboxReplayCommand.class);

    private final OutboxStore store;
    private final Clock clock;

    OutboxReplayCommand(OutboxStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "outbox-replay";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String value = OpsCommand.required(arguments, "destination");
        Destination destination = Destination.valueOf(value.toUpperCase(Locale.ROOT));
        log.info("Phát lại {} sự kiện tới {}", store.replay(destination, clock.millis()), destination.id());
    }
}

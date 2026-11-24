package com.learninge.server.common.outbox;

import com.learninge.server.common.ops.OpsCommand;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=outbox-list}: in số dòng chờ, dòng "chết" của từng đích và các dòng "chết" mới nhất.
 */
final class OutboxListCommand implements OpsCommand {
    private static final int MAX_DEAD = 50;

    private final OutboxStore store;
    private final Clock clock;
    private final PrintStream out;

    OutboxListCommand(OutboxStore store, Clock clock, PrintStream out) {
        this.store = store;
        this.clock = clock;
        this.out = out;
    }

    @Override
    public String name() {
        return "outbox-list";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        out.printf("%-14s %8s %14s %8s%n", "đích", "chờ", "chờ lâu nhất", "chết");
        for (Destination destination : Destination.values()) {
            long seconds =
                    store.oldestPending(destination).map(at -> (clock.millis() - at) / 1000).orElse(0L);
            out.printf(
                    "%-14s %8d %12d s %8d%n",
                    destination.id(),
                    store.countPending(destination),
                    seconds,
                    store.countDead(destination));
        }
        for (OutboxStore.DeadRow row : store.dead(MAX_DEAD)) {
            out.printf(
                    "chết %s %s %s %s %s%n",
                    Instant.ofEpochMilli(row.deadAt()),
                    row.eventId(),
                    row.destination(),
                    row.type(),
                    row.error());
        }
    }
}

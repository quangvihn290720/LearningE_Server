package com.learninge.server.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.server.common.outbox.OutboxDrainer.Outcome;
import com.learninge.server.common.sqlite.SqliteDatabase;
import com.learninge.server.common.testing.MutableClock;
import io.grpc.Status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxTest {
    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at("2026-11-23T00:00:00Z");
    private final FakePublisher publisher = new FakePublisher();
    private SqliteDatabase db;
    private OutboxStore store;
    private OutboxWriter writer;
    private OutboxDrainer drainer;

    @BeforeEach
    void setUp() {
        db = SqliteDatabase.open(dir.resolve("o.db"), "db/migration/outboxtest");
        store = new OutboxStore(db);
        writer = new OutboxWriter(store, clock, () -> {
        });
        drainer = new OutboxDrainer(store, publisher, new Backoff(15_000, () -> 0.5), clock, 200);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void append() {
        db.inWriteTx(this::appendInTx);
    }

    private void appendInTx() {
        writer.append(
                EventType.USER_DELETED,
                "user",
                (destination, event) ->
                        event.setUserDeleted(UserDeleted.newBuilder().setUserId(destination.id())));
    }

    @Test
    void writesOneRowPerDestinationWithSameEventIdAndOwnPayload() {
        append();
        drainer.drain(Destination.SYNC);
        drainer.drain(Destination.INSIGHT);
        Event sync = publisher.received(Destination.SYNC).getFirst();
        Event insight = publisher.received(Destination.INSIGHT).getFirst();
        assertThat(sync.getEventId()).isEqualTo(insight.getEventId());
        assertThat(sync.getUserDeleted().getUserId()).isEqualTo("sync");
        assertThat(insight.getAggregateId()).isEmpty();
        assertThat(store.countPending(Destination.GAMIFICATION)).isEqualTo(1);
    }

    @Test
    void rollbackLeavesNoOrphanEvents() {
        Runnable failing =
                () -> {
                    appendInTx();
                    throw new IllegalStateException("boom");
                };
        assertThatThrownBy(() -> db.inWriteTx(failing)).hasMessage("boom");
        assertThat(store.countPending(Destination.SYNC)).isZero();
    }

    @Test
    void refusesToWriteOutsideTransaction() {
        assertThatThrownBy(this::appendInTx).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transientFailureBacksOffAndKeepsOrder() {
        append();
        append();
        publisher.fail(Destination.SYNC, Status.UNAVAILABLE);
        publisher.serving = false;
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.WAITING);
        assertThat(attempts(Destination.SYNC)).containsExactly(1, 1);

        publisher.recover(Destination.SYNC);
        append(); // sự kiện mới không được vượt lên trước
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.WAITING);
        assertThat(publisher.received(Destination.SYNC)).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(publisher.received(Destination.SYNC)).hasSize(3);
    }

    @Test
    void retriesImmediatelyWhenDestinationServesAgain() {
        append();
        publisher.fail(Destination.SYNC, Status.UNAVAILABLE);
        publisher.serving = false;
        for (int i = 0; i < 6; i++) {
            drainer.drain(Destination.SYNC);
            clock.advance(Duration.ofSeconds(20));
        }
        drainer.drain(Destination.SYNC);
        publisher.recover(Destination.SYNC);
        publisher.serving = true;
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(store.countPending(Destination.SYNC)).isZero();
    }

    @Test
    void failingDestinationDoesNotBlockOthers() {
        append();
        publisher.fail(Destination.GAMIFICATION, Status.UNAVAILABLE);
        drainer.drain(Destination.GAMIFICATION);
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(drainer.drain(Destination.INSIGHT)).isEqualTo(Outcome.PROGRESS);
        assertThat(store.countPending(Destination.GAMIFICATION)).isEqualTo(1);
    }

    @Test
    void poisonEventDiesAfterFiveAttemptsAndLaterRowsPass() {
        append();
        append();
        String poison = pendingIds(Destination.SYNC).getFirst();
        publisher.poison.add(poison);
        for (int i = 0; i < 5; i++) {
            drainer.drain(Destination.SYNC);
            clock.advance(Duration.ofSeconds(20));
        }
        drainer.drain(Destination.SYNC);
        assertThat(store.countPending(Destination.SYNC)).isZero();
        assertThat(publisher.received(Destination.SYNC))
                .extracting(Event::getEventId)
                .doesNotContain(poison)
                .hasSize(1);

        publisher.poison.clear();
        assertThat(store.requeue(poison, clock.millis())).isPositive();
        drainer.drain(Destination.SYNC);
        assertThat(publisher.received(Destination.SYNC)).hasSize(2);
    }

    @Test
    void undecodablePayloadIsIsolated() {
        append();
        db.writer().sql("UPDATE outbox SET payload = x'ffff' WHERE destination = 'sync'").update();
        append();
        for (int i = 0; i < 6; i++) {
            drainer.drain(Destination.SYNC);
            clock.advance(Duration.ofSeconds(20));
        }
        assertThat(publisher.received(Destination.SYNC)).hasSize(1);
        assertThat(store.countPending(Destination.SYNC)).isZero();
    }

    @Test
    void lostAckCausesRedelivery() {
        append();
        publisher.dropAcks = true;
        publisher.serving = false;
        drainer.drain(Destination.SYNC);
        publisher.dropAcks = false;
        clock.advance(Duration.ofSeconds(3));
        drainer.drain(Destination.SYNC);
        assertThat(publisher.received(Destination.SYNC)).hasSize(2);
        assertThat(store.countPending(Destination.SYNC)).isZero();
    }

    /**
     * Bên nhận hết giờ giữa lô: phần đã ack không gửi lại, phần còn lại gửi tiếp ngay, không lùi giờ.
     */
    @Test
    void partialAckIsProgress() {
        for (int i = 0; i < 5; i++) {
            append();
        }
        publisher.maxAcks = 2;
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(store.countPending(Destination.SYNC)).isEqualTo(3);
        assertThat(attempts(Destination.SYNC)).containsExactly(0, 0, 0);
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.PROGRESS);
        assertThat(drainer.drain(Destination.SYNC)).isEqualTo(Outcome.EMPTY);
    }

    @Test
    void oldestPendingTellsHowLongTheQueueHasBeenStuck() {
        assertThat(store.oldestPending(Destination.SYNC)).isEmpty();
        append();
        long first = clock.millis();
        clock.advance(Duration.ofMinutes(6));
        append();
        assertThat(store.oldestPending(Destination.SYNC)).contains(first);
        drainer.drain(Destination.SYNC);
        assertThat(store.oldestPending(Destination.SYNC)).isEmpty();
    }

    @Test
    void deletesOnlyPublishedRowsPastRetention() {
        append();
        drainer.drain(Destination.SYNC);
        clock.advance(Duration.ofDays(7).minusHours(1));
        assertThat(store.deletePublishedBefore(clock.millis() - Duration.ofDays(7).toMillis()))
                .isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(store.deletePublishedBefore(clock.millis() - Duration.ofDays(7).toMillis()))
                .isEqualTo(1);
        assertThat(store.countPending(Destination.INSIGHT)).isEqualTo(1);
    }

    @Test
    void relayDeliversAfterCommitWithoutWaitingForThePollInterval() {
        OutboxRelay relay = new OutboxRelay(drainer, List.of(Destination.SYNC), true);
        OutboxWriter waking = new OutboxWriter(store, clock, relay::wakeUp);
        relay.start();
        try {
            db.inWriteTx(
                    () ->
                            waking.append(
                                    EventType.USER_DELETED,
                                    "u",
                                    (d, e) -> e.setUserDeleted(UserDeleted.getDefaultInstance())));
            Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .until(() -> publisher.received(Destination.SYNC).size() == 1);
        } finally {
            relay.stop();
        }
    }

    private List<Integer> attempts(Destination destination) {
        return store.pending(destination, 10).stream().map(OutboxRow::attempts).toList();
    }

    private List<String> pendingIds(Destination destination) {
        return store.pending(destination, 10).stream().map(OutboxRow::eventId).toList();
    }

    private static final class FakePublisher implements EventPublisher {
        private final Map<Destination, List<Event>> received = new EnumMap<>(Destination.class);
        private final Map<Destination, Status> failures = new EnumMap<>(Destination.class);
        private final Set<String> poison = new HashSet<>();
        private boolean serving = true;
        private boolean dropAcks;
        private int maxAcks = Integer.MAX_VALUE;

        @Override
        public synchronized Set<String> publish(Destination destination, List<Event> events) {
            if (failures.containsKey(destination)) {
                throw failures.get(destination).asRuntimeException();
            }
            if (events.stream().anyMatch(e -> poison.contains(e.getEventId()))) {
                throw Status.INVALID_ARGUMENT.asRuntimeException();
            }
            received.computeIfAbsent(destination, d -> new ArrayList<>()).addAll(events);
            Set<String> acks = new HashSet<>();
            if (!dropAcks) {
                events.stream().limit(maxAcks).forEach(e -> acks.add(e.getEventId()));
            }
            return acks;
        }

        @Override
        public boolean isServing(Destination destination) {
            return serving;
        }

        void fail(Destination destination, Status status) {
            failures.put(destination, status);
        }

        void recover(Destination destination) {
            failures.remove(destination);
        }

        synchronized List<Event> received(Destination destination) {
            return List.copyOf(received.getOrDefault(destination, List.of()));
        }
    }
}

package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Một bước phát sự kiện cho một đích. Giữ thứ tự theo {@code id}: dòng đầu hàng đợi chưa tới lượt
 * thì cả hàng chờ. Dòng bị từ chối vĩnh viễn 5 lần thì "chết" để không chặn dòng sau.
 */
final class OutboxDrainer {
  enum Outcome {
    EMPTY,
    PROGRESS,
    WAITING
  }

  private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
  private static final int MAX_PERMANENT_ATTEMPTS = 5;

  private final OutboxStore store;
  private final EventPublisher publisher;
  private final Backoff backoff;
  private final Clock clock;
  private final int batchSize;

  OutboxDrainer(
      OutboxStore store, EventPublisher publisher, Backoff backoff, Clock clock, int batchSize) {
    this.store = store;
    this.publisher = publisher;
    this.backoff = backoff;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  Outcome drain(Destination destination) {
    List<OutboxRow> rows = store.pending(destination, batchSize);
    if (rows.isEmpty()) {
      return Outcome.EMPTY;
    }
    if (rows.getFirst().nextAttemptAt() > clock.millis()) {
      if (!publisher.isServing(destination)) {
        return Outcome.WAITING;
      }
      store.resetBackoff(destination, clock.millis());
      rows = store.pending(destination, batchSize);
      if (rows.isEmpty() || rows.getFirst().nextAttemptAt() > clock.millis()) {
        return Outcome.WAITING;
      }
    }
    return sendBatch(destination, rows);
  }

  private Outcome sendBatch(Destination destination, List<OutboxRow> rows) {
    List<Event> events = new ArrayList<>();
    for (OutboxRow row : rows) {
      var event = row.event();
      if (event.isEmpty()) {
        // Payload hỏng: gửi riêng từng dòng để cô lập dòng lỗi.
        return rows.size() == 1
            ? permanentFailure(row, Status.Code.INVALID_ARGUMENT)
            : sendOneByOne(destination, rows);
      }
      events.add(event.get());
    }
    try {
      Set<String> acked = publisher.publish(destination, events);
      List<OutboxRow> done = rows.stream().filter(r -> acked.contains(r.eventId())).toList();
      store.markPublished(done, clock.millis());
      List<OutboxRow> missing = rows.stream().filter(r -> !acked.contains(r.eventId())).toList();
      return missing.isEmpty() ? Outcome.PROGRESS : transientFailure(missing, Status.Code.UNKNOWN);
    } catch (StatusRuntimeException e) {
      Status.Code code = e.getStatus().getCode();
      if (!isPermanent(code)) {
        return transientFailure(rows, code);
      }
      return rows.size() == 1
          ? permanentFailure(rows.getFirst(), code)
          : sendOneByOne(destination, rows);
    }
  }

  private Outcome sendOneByOne(Destination destination, List<OutboxRow> rows) {
    for (OutboxRow row : rows) {
      Outcome outcome = sendBatch(destination, List.of(row));
      if (outcome == Outcome.WAITING) {
        return outcome;
      }
    }
    return Outcome.PROGRESS;
  }

  private Outcome transientFailure(List<OutboxRow> rows, Status.Code code) {
    long delay = backoff.delayMillis(rows.getFirst().attempts() + 1);
    store.markFailed(rows, clock.millis() + delay, code.name());
    return Outcome.WAITING;
  }

  private Outcome permanentFailure(OutboxRow row, Status.Code code) {
    if (row.attempts() + 1 >= MAX_PERMANENT_ATTEMPTS) {
      store.markDead(row, clock.millis(), code.name());
      log.error("Sự kiện {} bị từ chối vĩnh viễn ({}), chuyển sang dead", row.eventId(), code);
      return Outcome.PROGRESS;
    }
    return transientFailure(List.of(row), code);
  }

  private static boolean isPermanent(Status.Code code) {
    return code == Status.Code.INVALID_ARGUMENT || code == Status.Code.FAILED_PRECONDITION;
  }
}

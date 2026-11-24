package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.PublishResponse;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.FieldErrors;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Khung nhận sự kiện: sự kiện sai luật bị bỏ nhưng vẫn ack (để relay của bên gửi không kẹt); lỗi hạ
 * tầng thì ném ra để relay gửi lại.
 *
 * <p>Sắp hết hạn của lô thì dừng và ack phần đã xong: lô lớn đến mấy relay cũng tiến được, không gửi
 * lại mãi cùng một lô.
 */
public abstract class EventSinkBase extends EventSinkGrpc.EventSinkImplBase {
    private static final Logger log = LoggerFactory.getLogger(EventSinkBase.class);
    private static final int MAX_EVENTS = 200;
    private static final long REPLY_MARGIN_MS = 1000;

    private final Destination self;
    private final int chunk;

    protected EventSinkBase(Destination self) {
        this(self, 1);
    }

    /**
     * @param chunk số sự kiện đưa vào một lần {@link #handleAll}
     */
    protected EventSinkBase(Destination self, int chunk) {
        this.self = self;
        this.chunk = chunk;
    }

    /**
     * Phải idempotent: cùng sự kiện có thể tới nhiều lần và lệch thứ tự.
     */
    protected abstract void handle(Event event);

    /**
     * Dịch vụ xử lý theo cụm thì ghi đè; cũng phải idempotent.
     */
    protected void handleAll(List<Event> events) {
        events.forEach(this::handle);
    }

    @Override
    public final void publish(
            PublishRequest request, StreamObserver<PublishResponse> responseObserver) {
        if (request.getEventsCount() > MAX_EVENTS) {
            throw AppException.invalid("events", FieldErrors.OUT_OF_RANGE);
        }
        PublishResponse.Builder response = PublishResponse.newBuilder();
        List<Event> events = request.getEventsList();
        for (int from = 0; from < events.size() && (from == 0 || hasTimeLeft()); from += chunk) {
            List<Event> part = events.subList(from, Math.min(from + chunk, events.size()));
            handleAll(accepted(part));
            part.forEach(event -> response.addAckedEventIds(event.getEventId()));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private List<Event> accepted(List<Event> events) {
        List<Event> accepted = new ArrayList<>(events.size());
        for (Event event : events) {
            EventPolicy.violation(event, self)
                    .ifPresentOrElse(
                            reason ->
                                    log.error("Bỏ sự kiện {} {}: {}", event.getEventId(), event.getType(), reason),
                            () -> accepted.add(event));
        }
        return accepted;
    }

    private static boolean hasTimeLeft() {
        Deadline deadline = Context.current().getDeadline();
        return deadline == null || deadline.timeRemaining(TimeUnit.MILLISECONDS) > REPLY_MARGIN_MS;
    }
}

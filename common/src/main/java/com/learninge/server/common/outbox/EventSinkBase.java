package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.PublishResponse;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.FieldErrors;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Khung nhận sự kiện: sự kiện sai luật bị bỏ nhưng vẫn ack (để relay của bên gửi không kẹt); lỗi hạ
 * tầng thì ném ra để relay gửi lại.
 */
public abstract class EventSinkBase extends EventSinkGrpc.EventSinkImplBase {
    private static final Logger log = LoggerFactory.getLogger(EventSinkBase.class);
    private static final int MAX_EVENTS = 200;

    private final Destination self;

    protected EventSinkBase(Destination self) {
        this.self = self;
    }

    /**
     * Phải idempotent: cùng sự kiện có thể tới nhiều lần và lệch thứ tự.
     */
    protected abstract void handle(Event event);

    @Override
    public final void publish(
            PublishRequest request, StreamObserver<PublishResponse> responseObserver) {
        if (request.getEventsCount() > MAX_EVENTS) {
            throw AppException.invalid("events", FieldErrors.OUT_OF_RANGE);
        }
        PublishResponse.Builder response = PublishResponse.newBuilder();
        for (Event event : request.getEventsList()) {
            EventPolicy.violation(event, self)
                    .ifPresentOrElse(
                            reason ->
                                    log.error("Bỏ sự kiện {} {}: {}", event.getEventId(), event.getType(), reason),
                            () -> handle(event));
            response.addAckedEventIds(event.getEventId());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}

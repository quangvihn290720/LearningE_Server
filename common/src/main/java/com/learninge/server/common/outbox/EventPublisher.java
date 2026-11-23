package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;

import java.util.List;
import java.util.Set;

/**
 * Đường gửi sự kiện; tách interface để test relay không cần mạng.
 */
public interface EventPublisher {
    /**
     * Trả các {@code event_id} đã được nhận; ném {@code StatusRuntimeException} khi lỗi.
     */
    Set<String> publish(Destination destination, List<Event> events);

    boolean isServing(Destination destination);
}

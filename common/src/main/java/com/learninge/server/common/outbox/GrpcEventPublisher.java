package com.learninge.server.common.outbox;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.EventSinkGrpc;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.server.common.grpc.InternalChannels;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Gửi qua {@code EventSink.Publish}, deadline 3 giây mỗi lô.
 */
public final class GrpcEventPublisher implements EventPublisher {
    private final InternalChannels channels;

    public GrpcEventPublisher(InternalChannels channels) {
        this.channels = channels;
    }

    @Override
    public Set<String> publish(Destination destination, List<Event> events) {
        var response =
                EventSinkGrpc.newBlockingStub(channels.to(destination.id()))
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .publish(PublishRequest.newBuilder().addAllEvents(events).build());
        return Set.copyOf(response.getAckedEventIdsList());
    }

    @Override
    public boolean isServing(Destination destination) {
        try {
            var response =
                    HealthGrpc.newBlockingStub(channels.to(destination.id()))
                            .withDeadlineAfter(1, TimeUnit.SECONDS)
                            .check(HealthCheckRequest.getDefaultInstance());
            return response.getStatus() == ServingStatus.SERVING;
        } catch (StatusRuntimeException e) {
            return false;
        }
    }
}

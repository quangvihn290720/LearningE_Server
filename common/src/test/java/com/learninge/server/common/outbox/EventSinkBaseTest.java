package com.learninge.server.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.PublishRequest;
import com.learninge.proto.internal.v1.PublishResponse;
import com.learninge.proto.internal.v1.UserDeleted;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventSinkBaseTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<List<Event>> handled = new ArrayList<>();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private EventSinkBase sink(int chunk, long millisPerChunk) {
        return new EventSinkBase(Destination.SYNC, chunk) {
            @Override
            protected void handle(Event event) {
                throw new AssertionError("handleAll đã thay");
            }

            @Override
            protected void handleAll(List<Event> events) {
                handled.add(List.copyOf(events));
                try {
                    Thread.sleep(millisPerChunk);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static Event deleted(String source) {
        return Event.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setType("user.deleted")
                .setSource(source)
                .setUserDeleted(UserDeleted.newBuilder().setUserId(UUID.randomUUID().toString()))
                .build();
    }

    private static PublishResponse publish(EventSinkBase sink, List<Event> events) {
        List<PublishResponse> replies = new ArrayList<>();
        sink.publish(
                PublishRequest.newBuilder().addAllEvents(events).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(PublishResponse value) {
                        replies.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        throw new AssertionError(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        return replies.getFirst();
    }

    private static List<Event> events(int count) {
        return IntStream.range(0, count).mapToObj(i -> deleted("auth")).toList();
    }

    @Test
    void handlesEverythingInChunksWhenThereIsTime() {
        List<Event> events = events(7);
        assertThat(publish(sink(3, 0), events).getAckedEventIdsList())
                .containsExactlyElementsOf(events.stream().map(Event::getEventId).toList());
        assertThat(handled).extracting(List::size).containsExactly(3, 3, 1);
    }

    @Test
    void acksWhatIsDoneBeforeTheDeadlineSoTheRelayAlwaysAdvances() throws Exception {
        List<Event> events = events(50);
        PublishResponse response =
                Context.current()
                        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS, scheduler)
                        .call(() -> publish(sink(1, 200), events));
        assertThat(response.getAckedEventIdsCount()).isBetween(1, 10).isEqualTo(handled.size());
    }

    @Test
    void theFirstChunkRunsEvenWhenTheDeadlineIsAlreadyClose() throws Exception {
        PublishResponse response =
                Context.current()
                        .withDeadlineAfter(100, TimeUnit.MILLISECONDS, scheduler)
                        .call(() -> publish(sink(2, 0), events(5)));
        assertThat(response.getAckedEventIdsCount()).isEqualTo(2);
    }

    @Test
    void refusedEventsAreAckedButNotHandled() {
        Event wrongSource = deleted("sync");
        Event good = deleted("auth");
        assertThat(publish(sink(10, 0), List.of(wrongSource, good)).getAckedEventIdsCount()).isEqualTo(2);
        assertThat(handled).containsExactly(List.of(good));
    }
}

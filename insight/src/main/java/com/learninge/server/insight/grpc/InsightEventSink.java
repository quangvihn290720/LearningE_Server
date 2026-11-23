package com.learninge.server.insight.grpc;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventSinkBase;
import com.learninge.server.insight.service.EventRecorder;

/** Nhận mọi loại sự kiện. */
public final class InsightEventSink extends EventSinkBase {
  private final EventRecorder recorder;

  public InsightEventSink(EventRecorder recorder) {
    super(Destination.INSIGHT);
    this.recorder = recorder;
  }

  @Override
  protected void handle(Event event) {
    recorder.record(event);
  }
}

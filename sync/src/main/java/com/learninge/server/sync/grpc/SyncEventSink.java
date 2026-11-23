package com.learninge.server.sync.grpc;

import com.learninge.proto.internal.v1.Event;
import com.learninge.server.common.outbox.Destination;
import com.learninge.server.common.outbox.EventSinkBase;
import com.learninge.server.sync.service.UserLifecycle;

/** Nhận {@code user.registered} và {@code user.deleted} từ auth. */
public final class SyncEventSink extends EventSinkBase {
  private final UserLifecycle lifecycle;

  public SyncEventSink(UserLifecycle lifecycle) {
    super(Destination.SYNC);
    this.lifecycle = lifecycle;
  }

  @Override
  protected void handle(Event event) {
    switch (event.getPayloadCase()) {
      case USER_REGISTERED -> lifecycle.onRegistered(event.getUserRegistered().getUserId());
      case USER_DELETED -> lifecycle.onDeleted(event.getUserDeleted().getUserId());
      default -> throw new IllegalStateException("EventPolicy đã chặn " + event.getType());
    }
  }
}

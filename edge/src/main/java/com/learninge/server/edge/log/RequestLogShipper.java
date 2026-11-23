package com.learninge.server.edge.log;

import com.learninge.proto.internal.v1.InsightBackendGrpc;
import com.learninge.proto.internal.v1.LogRequestsRequest;
import com.learninge.proto.internal.v1.RequestLog;
import com.learninge.server.common.grpc.CallInfo;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.grpc.RequestIdServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Gửi nhật ký yêu cầu sang insight theo kiểu cố gắng hết sức: hàng đợi có trần, đầy thì bỏ và đếm.
 * Mất log không bao giờ ảnh hưởng người dùng. Không có IP, token hay nội dung message.
 */
public final class RequestLogShipper
    implements RequestIdServerInterceptor.CallObserver, SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(RequestLogShipper.class);
  private static final int QUEUE = 1000;
  private static final int BATCH = 100;
  private static final int FLUSH_SECONDS = 2;

  private final InternalChannels channels;
  private final Clock clock;
  private final boolean enabled;
  private final BlockingQueue<RequestLog> queue = new ArrayBlockingQueue<>(QUEUE);
  private final AtomicLong dropped = new AtomicLong();
  private volatile Thread thread;

  public RequestLogShipper(InternalChannels channels, Clock clock, boolean enabled) {
    this.channels = channels;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Override
  public void onClosed(CallInfo info, Status.Code code) {
    if (!enabled || info.rpc().startsWith("grpc.")) {
      return;
    }
    RequestLog entry =
        RequestLog.newBuilder()
            .setRequestId(info.requestId())
            .setRpc(info.rpc())
            .setCode(code.value())
            .setDurationMs(info.durationMs())
            .setUserHmac(info.userHmac())
            .setClientVersion(info.clientVersion())
            .setTsMs(clock.millis())
            .build();
    if (!queue.offer(entry)) {
      dropped.incrementAndGet();
    }
  }

  @Override
  public synchronized void start() {
    thread = Thread.ofPlatform().name("request-logs").daemon().start(this::loop);
  }

  private void loop() {
    List<RequestLog> batch = new ArrayList<>(BATCH);
    while (thread != null) {
      try {
        RequestLog first = queue.poll(FLUSH_SECONDS, TimeUnit.SECONDS);
        if (first != null) {
          batch.add(first);
          queue.drainTo(batch, BATCH - 1);
          send(batch);
          batch.clear();
        }
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void send(List<RequestLog> batch) {
    try {
      InsightBackendGrpc.newBlockingStub(channels.to("insight"))
          .withDeadlineAfter(2, TimeUnit.SECONDS)
          .logRequests(LogRequestsRequest.newBuilder().addAllLogs(batch).build());
    } catch (StatusRuntimeException e) {
      long total = dropped.addAndGet(batch.size());
      log.debug("request_logs_dropped={}", total);
    }
  }

  @Override
  public synchronized void stop() {
    Thread running = thread;
    thread = null;
    if (running != null) {
      running.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return thread != null;
  }

  @Override
  public boolean isAutoStartup() {
    return enabled;
  }
}

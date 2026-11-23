package com.learninge.server.common.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Mỗi đích một luồng riêng nên đích chậm hoặc ngừng không chặn đích khác. Luồng chạy khi được đánh
 * thức sau commit, hoặc tối đa 1 giây một lần.
 */
public final class OutboxRelay implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDrainer drainer;
    private final List<Destination> destinations;
    private final boolean enabled;
    private final Map<Destination, Semaphore> signals = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    OutboxRelay(OutboxDrainer drainer, List<Destination> destinations, boolean enabled) {
        this.drainer = drainer;
        this.destinations = destinations;
        this.enabled = enabled;
        destinations.forEach(d -> signals.put(d, new Semaphore(0)));
    }

    public void wakeUp() {
        signals.values().forEach(Semaphore::release);
    }

    @Override
    public synchronized void start() {
        running = true;
        for (Destination destination : destinations) {
            threads.add(
                    Thread.ofPlatform()
                            .name("outbox-" + destination.id())
                            .daemon()
                            .start(() -> loop(destination)));
        }
    }

    private void loop(Destination destination) {
        Semaphore signal = signals.get(destination);
        while (running) {
            try {
                OutboxDrainer.Outcome outcome = drainer.drain(destination);
                if (outcome != OutboxDrainer.Outcome.PROGRESS) {
                    signal.tryAcquire(1, TimeUnit.SECONDS);
                    signal.drainPermits();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Relay tới {} lỗi, thử lại sau 1 giây", destination.id(), e);
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        wakeUp();
        for (Thread thread : threads) {
            try {
                // Lô đang gửi có deadline 3 giây; chờ nó xong để không gửi lại vô ích.
                thread.join(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread.interrupt();
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }
}

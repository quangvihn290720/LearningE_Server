package com.learninge.server.common.ops;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Việc nền định kỳ (dọn dữ liệu hết hạn, cảnh báo tồn đọng). Một việc lỗi không dừng việc khác.
 */
public final class BackgroundTasks implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BackgroundTasks.class);

    private record Task(String name, Duration period, Runnable body) {
    }

    private final boolean enabled;
    private final List<Task> tasks = new ArrayList<>();
    private ScheduledExecutorService executor;

    public BackgroundTasks(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Chạy ngay khi khởi động rồi lặp lại sau mỗi {@code period}. Chỉ gọi lúc dựng bean.
     */
    public void every(String name, Duration period, Runnable body) {
        tasks.add(new Task(name, period, body));
    }

    @Override
    public synchronized void start() {
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("background").daemon().factory());
        for (Task task : tasks) {
            executor.scheduleWithFixedDelay(
                    () -> runSafely(task), 0, task.period().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void runSafely(Task task) {
        try {
            task.body().run();
        } catch (RuntimeException e) {
            log.error("Việc nền {} lỗi", task.name(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }
}

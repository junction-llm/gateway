package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.ApiKeyUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded asynchronous API-key usage recorder.
 *
 * <p>Successful requests increment an in-memory per-key counter on the request
 * path. A single background worker flushes those counts to the repository. This
 * removes repository writes from validation while preserving historical request
 * count semantics for flushed records.
 */
public class AsyncApiKeyUsageRecorder implements ApiKeyUsageRecorder, SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AsyncApiKeyUsageRecorder.class);

    private final ApiKeyRepository repository;
    private final int maxPendingKeys;
    private final Duration flushInterval;
    private final Duration shutdownTimeout;
    private final Map<String, AtomicLong> pendingCounts = new ConcurrentHashMap<>();
    private final Queue<String> flushOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingKeyCount = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public AsyncApiKeyUsageRecorder(ApiKeyRepository repository,
                                    int maxPendingKeys,
                                    Duration flushInterval,
                                    Duration shutdownTimeout) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        if (maxPendingKeys < 1) {
            throw new IllegalArgumentException("maxPendingKeys must be greater than zero");
        }
        this.maxPendingKeys = maxPendingKeys;
        this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval cannot be null");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout cannot be null");
    }

    @Override
    public void recordUsage(String apiKeyId) {
        Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
        if (!running.get()) {
            return;
        }

        AtomicLong existing = pendingCounts.get(apiKeyId);
        if (existing != null) {
            existing.incrementAndGet();
            return;
        }

        if (!reservePendingKey()) {
            logger.warn("Dropping API-key usage update because pending-key limit {} is full", maxPendingKeys);
            return;
        }

        AtomicLong created = new AtomicLong(1L);
        AtomicLong raced = pendingCounts.putIfAbsent(apiKeyId, created);
        if (raced == null) {
            flushOrder.offer(apiKeyId);
        } else {
            releasePendingKey();
            raced.incrementAndGet();
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "junction-api-key-usage-recorder");
                thread.setDaemon(true);
                return thread;
            });
            long periodMillis = Math.max(100L, flushInterval.toMillis());
            executor.scheduleWithFixedDelay(this::flushSafely, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            ScheduledExecutorService localExecutor = executor;
            if (localExecutor != null) {
                localExecutor.shutdown();
            }
            flushSafely();
            if (localExecutor != null) {
                try {
                    if (!localExecutor.awaitTermination(Math.max(1L, shutdownTimeout.toMillis()), TimeUnit.MILLISECONDS)) {
                        localExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    localExecutor.shutdownNow();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private boolean reservePendingKey() {
        while (true) {
            int current = pendingKeyCount.get();
            if (current >= maxPendingKeys) {
                return false;
            }
            if (pendingKeyCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releasePendingKey() {
        pendingKeyCount.decrementAndGet();
    }

    private void flushSafely() {
        try {
            flushPending();
        } catch (RuntimeException e) {
            logger.warn("Failed to flush API-key usage updates", e);
        }
    }

    void flushPending() {
        List<UsageCount> counts = drainPendingCounts();
        if (counts.isEmpty()) {
            return;
        }

        Map<String, Long> batch = new LinkedHashMap<>();
        for (UsageCount usage : counts) {
            batch.merge(usage.apiKeyId(), usage.count(), Long::sum);
        }

        try {
            repository.incrementUsageBatch(batch);
        } catch (RuntimeException e) {
            logger.warn("Failed to record API-key usage batch for {} key(s)", batch.size(), e);
        }
    }

    int pendingKeyCount() {
        return pendingKeyCount.get();
    }

    private List<UsageCount> drainPendingCounts() {
        List<UsageCount> counts = new ArrayList<>();
        String id;
        while ((id = flushOrder.poll()) != null) {
            AtomicLong count = pendingCounts.remove(id);
            if (count != null) {
                releasePendingKey();
                long value = count.get();
                if (value > 0) {
                    counts.add(new UsageCount(id, value));
                }
            }
        }
        return counts;
    }

    private record UsageCount(String apiKeyId, long count) {
    }
}

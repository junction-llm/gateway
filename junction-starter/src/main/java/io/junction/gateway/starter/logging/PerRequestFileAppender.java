package io.junction.gateway.starter.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback appender that creates per-request log files organized by date.
 * Each request gets its own file: logs/YYYY-MM-DD/{traceId}.log
 *
 * <p>This appender is intended only for short diagnostic captures. It bounds
 * active request writers and per-writer queues so an opt-in diagnostic profile
 * cannot create unbounded files, virtual threads, queued messages, or
 * request-thread disk writes under load.</p>
 *
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class PerRequestFileAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final int DEFAULT_MAX_ACTIVE_WRITERS = 128;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 250L;
    private static final long WARN_EVERY_DROPS = 100L;

    private String basePath = "logs";
    private int maxActiveWriters = DEFAULT_MAX_ACTIVE_WRITERS;
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    private long flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
    private ExecutorService executorService;
    private final ConcurrentHashMap<UUID, RequestLogWriter> activeWriters = new ConcurrentHashMap<>();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong rejectedTraceIds = new AtomicLong();

    @Override
    public void start() {
        maxActiveWriters = positiveOrDefault(maxActiveWriters, DEFAULT_MAX_ACTIVE_WRITERS);
        queueCapacity = positiveOrDefault(queueCapacity, DEFAULT_QUEUE_CAPACITY);
        flushIntervalMillis = positiveOrDefault(flushIntervalMillis, DEFAULT_FLUSH_INTERVAL_MILLIS);

        super.start();
        executorService = Executors.newVirtualThreadPerTaskExecutor();

        try {
            Files.createDirectories(Paths.get(basePath));
        } catch (IOException e) {
            addError("Failed to create base log directory: " + basePath, e);
        }
    }

    @Override
    public void stop() {
        activeWriters.values().forEach(RequestLogWriter::close);
        activeWriters.clear();

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        UUID traceId = getEventTraceId(event);

        if (traceId != null) {
            writeToRequestLog(traceId, event);
        }
    }

    private UUID getEventTraceId(ILoggingEvent event) {
        try {
            String traceId = event.getMDCPropertyMap().get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                return UUID.fromString(traceId);
            }
        } catch (Exception e) {
            addWarn("Invalid traceId in MDC for event: " + event.getFormattedMessage(), e);
        }
        return null;
    }

    private void writeToRequestLog(UUID traceId, ILoggingEvent event) {
        RequestLogWriter writer = activeWriters.get(traceId);
        if (writer == null) {
            if (activeWriters.size() >= maxActiveWriters) {
                recordRejectedTraceId(traceId);
                return;
            }
            writer = activeWriters.computeIfAbsent(traceId, ignored -> createWriter(traceId, event));
        }

        if (writer != null && !writer.append(event)) {
            recordDroppedEvent(traceId);
        }
    }

    private RequestLogWriter createWriter(UUID traceId, ILoggingEvent event) {
        String dateFolder = Instant.ofEpochMilli(event.getTimeStamp())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DATE_FORMATTER);
        Path logFile = Paths.get(basePath, dateFolder, traceId.toString() + ".log");

        try {
            Files.createDirectories(logFile.getParent());
            return new RequestLogWriter(logFile, executorService, queueCapacity, flushIntervalMillis);
        } catch (IOException e) {
            addError("Failed to create request log file: " + logFile, e);
            return null;
        }
    }

    private void recordDroppedEvent(UUID traceId) {
        long count = droppedEvents.incrementAndGet();
        if (count == 1 || count % WARN_EVERY_DROPS == 0) {
            addWarn("Dropped " + count + " per-request log events because writer queues are full; latest traceId=" + traceId);
        }
    }

    private void recordRejectedTraceId(UUID traceId) {
        long count = rejectedTraceIds.incrementAndGet();
        if (count == 1 || count % WARN_EVERY_DROPS == 0) {
            addWarn("Rejected " + count + " per-request traceIds because maxActiveWriters=" + maxActiveWriters
                + " is reached; latest traceId=" + traceId);
        }
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public void setMaxActiveWriters(int maxActiveWriters) {
        this.maxActiveWriters = maxActiveWriters;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }

    int getActiveWriterCount() {
        return activeWriters.size();
    }

    long getDroppedEvents() {
        return droppedEvents.get();
    }

    long getRejectedTraceIds() {
        return rejectedTraceIds.get();
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static long positiveOrDefault(long value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static class RequestLogWriter {
        private final Path file;
        private final BlockingQueue<String> queue;
        private final long flushIntervalMillis;
        private volatile boolean closed = false;
        private final Future<?> writerTask;

        RequestLogWriter(Path file, ExecutorService executor, int queueCapacity, long flushIntervalMillis) throws IOException {
            this.file = file;
            this.queue = new LinkedBlockingQueue<>(queueCapacity);
            this.flushIntervalMillis = flushIntervalMillis;

            this.writerTask = executor.submit(this::writeLoop);
        }

        boolean append(ILoggingEvent event) {
            if (closed) return false;

            return queue.offer(formatEvent(event));
        }

        private void writeLoop() {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                boolean dirty = false;
                long lastFlushNanos = System.nanoTime();

                while (!closed || !queue.isEmpty()) {
                    String line = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                        dirty = true;
                    }

                    long now = System.nanoTime();
                    if (dirty && (closed || TimeUnit.NANOSECONDS.toMillis(now - lastFlushNanos) >= flushIntervalMillis)) {
                        writer.flush();
                        dirty = false;
                        lastFlushNanos = now;
                    }
                }

                if (dirty) {
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("Error writing to request log " + file + ": " + e.getMessage());
            }
        }

        private String formatEvent(ILoggingEvent event) {
            String timestamp = java.time.ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.getTimeStamp()),
                    ZoneOffset.UTC
            ).format(TIMESTAMP_FORMATTER);

            String level = event.getLevel().toString();
            String message = event.getFormattedMessage();

            if (event.getThrowableProxy() != null) {
                message += " | Exception: " + event.getThrowableProxy().getClassName() +
                          " - " + event.getThrowableProxy().getMessage();
            }

            return String.format("%s [%s] %s", timestamp, level, message);
        }

        void close() {
            closed = true;
            if (writerTask != null) {
                try {
                    writerTask.get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
        }
    }
}

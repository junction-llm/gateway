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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Logback appender that creates per-request log files organized by date.
 * Each request gets its own file: logs/YYYY-MM-DD/{traceId}.log
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class PerRequestFileAppender extends AppenderBase<ILoggingEvent> {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    
    private String basePath = "logs";
    private ExecutorService executorService;
    private final ConcurrentHashMap<UUID, RequestLogWriter> activeWriters = new ConcurrentHashMap<>();
    
    @Override
    public void start() {
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
        RequestLogWriter writer = activeWriters.computeIfAbsent(traceId, this::createWriter);
        writer.append(event);
    }
    
    private RequestLogWriter createWriter(UUID traceId) {
        String dateFolder = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMATTER);
        Path logFile = Paths.get(basePath, dateFolder, traceId.toString() + ".log");
        
        try {
            Files.createDirectories(logFile.getParent());
            return new RequestLogWriter(logFile, executorService);
        } catch (IOException e) {
            addError("Failed to create request log file: " + logFile, e);
            return null;
        }
    }
    
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
    
    private static class RequestLogWriter {
        private final Path file;
        private final BlockingQueue<String> queue;
        private final ExecutorService executor;
        private volatile boolean closed = false;
        private final Future<?> writerTask;
        
        RequestLogWriter(Path file, ExecutorService executor) throws IOException {
            this.file = file;
            this.executor = executor;
            this.queue = new LinkedBlockingQueue<>(5000);
            
            this.writerTask = executor.submit(this::writeLoop);
        }
        
        void append(ILoggingEvent event) {
            if (closed) return;
            
            String formatted = formatEvent(event);
            if (!queue.offer(formatted)) {
                writeSync(formatted);
            }
        }
        
        private void writeLoop() {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                
                while (!closed || !queue.isEmpty()) {
                    String line = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    }
                }
                writer.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("Error writing to request log " + file + ": " + e.getMessage());
            }
        }
        
        private void writeSync(String line) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

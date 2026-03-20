package io.junction.gateway.starter.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerRequestFileAppenderTest {

    private static final TimeZone TORONTO = TimeZone.getTimeZone("America/Toronto");

    @TempDir
    Path tempDir;

    private TimeZone originalTimeZone;

    @BeforeEach
    void setDefaultTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TORONTO);
    }

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void usesLocalRuntimeDayForFolderNameNearUtcMidnight() throws IOException {
        UUID traceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-03-18T00:30:00Z");

        appendAndStop(logFileEvent(traceId, timestamp, "local-day-folder"));

        Path expectedFile = logFile("2026-03-17", traceId);
        Path unexpectedFile = logFile("2026-03-18", traceId);

        assertTrue(Files.exists(expectedFile));
        assertFalse(Files.exists(unexpectedFile));
    }

    @Test
    void derivesFolderDateFromEventTimestampInsteadOfWallClock() throws IOException {
        UUID traceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2001-02-03T12:00:00Z");

        appendAndStop(logFileEvent(traceId, timestamp, "event-timestamp-folder"));

        assertTrue(Files.exists(logFile("2001-02-03", traceId)));
    }

    @Test
    void keepsWrittenTimestampsInUtc() throws IOException {
        UUID traceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-03-18T00:30:00Z");

        appendAndStop(logFileEvent(traceId, timestamp, "utc-timestamp"));

        String content = Files.readString(logFile("2026-03-17", traceId)).trim();
        assertEquals("2026-03-18T00:30:00.000Z [INFO] utc-timestamp", content);
    }

    @Test
    void writesRequestsAcrossLocalDayBoundaryIntoSeparateDateFolders() throws IOException {
        UUID beforeMidnightTraceId = UUID.randomUUID();
        UUID afterMidnightTraceId = UUID.randomUUID();

        appendAndStop(
            logFileEvent(beforeMidnightTraceId, Instant.parse("2026-01-15T04:59:59Z"), "before-midnight"),
            logFileEvent(afterMidnightTraceId, Instant.parse("2026-01-15T05:00:00Z"), "after-midnight")
        );

        assertTrue(Files.exists(logFile("2026-01-14", beforeMidnightTraceId)));
        assertTrue(Files.exists(logFile("2026-01-15", afterMidnightTraceId)));
    }

    private void appendAndStop(LoggingEvent... events) {
        PerRequestFileAppender appender = new PerRequestFileAppender();
        appender.setBasePath(tempDir.toString());
        appender.start();

        for (LoggingEvent event : events) {
            appender.doAppend(event);
        }

        appender.stop();
    }

    private LoggingEvent logFileEvent(UUID traceId, Instant timestamp, String message) {
        LoggerContext loggerContext = new LoggerContext();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(loggerContext);
        event.setLoggerName("test-logger");
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(timestamp.toEpochMilli());
        event.setMDCPropertyMap(Map.of("traceId", traceId.toString()));
        return event;
    }

    private Path logFile(String dateFolder, UUID traceId) {
        return tempDir.resolve(dateFolder).resolve(traceId + ".log");
    }
}

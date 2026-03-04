package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Rate limiter interface for API key request throttling.
 * 
 * <p>Supports three time windows (industry standard):
 * <ul>
 *   <li>Per-minute: Burst protection</li>
 *   <li>Per-day: Daily quota management</li>
 *   <li>Per-month: Monthly billing cycle</li>
 * </ul>
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public interface RateLimiter {
    
    /**
     * Checks if a request is allowed and increments the counter atomically.
     * 
     * @param apiKeyId the API key identifier
     * @param tier the API key tier (determines limits)
     * @return result containing allowance status and remaining quota
     */
    RateLimitResult checkAndIncrement(String apiKeyId, ApiKey.Tier tier);
    
    /**
     * Checks current quota without incrementing.
     * 
     * @param apiKeyId the API key identifier
     * @param tier the API key tier
     * @return current quota status
     */
    QuotaStatus getQuotaStatus(String apiKeyId, ApiKey.Tier tier);
    
    /**
     * Resets all rate limit counters for an API key.
     * Useful for manual quota resets or tier changes.
     * 
     * @param apiKeyId the API key identifier
     */
    void reset(String apiKeyId);
    
    record RateLimitResult(
        boolean allowed,
        WindowStatus minuteWindow,
        WindowStatus dayWindow,
        WindowStatus monthWindow,
        String rejectionReason
    ) {
        public RateLimitResult {
            Objects.requireNonNull(minuteWindow, "minuteWindow cannot be null");
            Objects.requireNonNull(dayWindow, "dayWindow cannot be null");
            Objects.requireNonNull(monthWindow, "monthWindow cannot be null");
        }
        
        public static RateLimitResult allowed(WindowStatus minute, WindowStatus day, WindowStatus month) {
            return new RateLimitResult(true, minute, day, month, null);
        }
        
        public static RateLimitResult rejected(String reason, WindowStatus minute, WindowStatus day, WindowStatus month) {
            return new RateLimitResult(false, minute, day, month, reason);
        }
        
        public WindowStatus mostRestrictive() {
            if (!minuteWindow.allowed()) return minuteWindow;
            if (!dayWindow.allowed()) return dayWindow;
            return monthWindow;
        }
    }
    
    record WindowStatus(
        String window,
        boolean allowed,
        long remaining,
        long limit,
        long resetAt,
        double usagePercent
    ) {
        public WindowStatus {
            Objects.requireNonNull(window, "window cannot be null");
        }
        
        public static WindowStatus allowed(String window, long remaining, long limit, long resetAt) {
            double usage = limit > 0 ? ((double) (limit - remaining) / limit) * 100 : 0;
            return new WindowStatus(window, true, remaining, limit, resetAt, usage);
        }
        
        public static WindowStatus rejected(String window, long remaining, long limit, long resetAt) {
            double usage = limit > 0 ? ((double) (limit - remaining) / limit) * 100 : 100;
            return new WindowStatus(window, false, remaining, limit, resetAt, usage);
        }
    }
    
    record QuotaStatus(
        String apiKeyId,
        ApiKey.Tier tier,
        WindowStatus minuteWindow,
        WindowStatus dayWindow,
        WindowStatus monthWindow,
        Instant checkedAt
    ) {
        public QuotaStatus {
            Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
            Objects.requireNonNull(tier, "tier cannot be null");
            Objects.requireNonNull(minuteWindow, "minuteWindow cannot be null");
            Objects.requireNonNull(dayWindow, "dayWindow cannot be null");
            Objects.requireNonNull(monthWindow, "monthWindow cannot be null");
            Objects.requireNonNull(checkedAt, "checkedAt cannot be null");
        }
        
        public static QuotaStatus unlimited() {
            long farFuture = Instant.now().plusSeconds(86400 * 365).getEpochSecond();
            return new QuotaStatus(
                "unlimited",
                ApiKey.Tier.ENTERPRISE,
                WindowStatus.allowed("minute", Long.MAX_VALUE, Long.MAX_VALUE, farFuture),
                WindowStatus.allowed("day", Long.MAX_VALUE, Long.MAX_VALUE, farFuture),
                WindowStatus.allowed("month", Long.MAX_VALUE, Long.MAX_VALUE, farFuture),
                Instant.now()
            );
        }
        
        public boolean isExhausted() {
            return !minuteWindow.allowed() || !dayWindow.allowed() || !monthWindow.allowed();
        }
        
        public WindowStatus mostRestrictive() {
            if (!minuteWindow.allowed()) return minuteWindow;
            if (!dayWindow.allowed()) return dayWindow;
            return monthWindow;
        }
    }
    
    enum TimeWindow {
        MINUTE(60, "minute"),
        DAY(86400, "day"),
        MONTH(2592000, "month");
        
        private final int seconds;
        private final String name;
        
        TimeWindow(int seconds, String name) {
            this.seconds = seconds;
            this.name = name;
        }
        
        public int seconds() { return seconds; }
        public String windowName() { return name; }
        
        public long currentWindowStart() {
            long now = Instant.now().getEpochSecond();
            return (now / seconds) * seconds;
        }
        
        public long currentWindowReset() {
            return currentWindowStart() + seconds;
        }
    }
}

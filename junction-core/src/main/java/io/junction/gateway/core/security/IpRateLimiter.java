package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiter for unauthenticated or additional rate limiting.
 * 
 * <p>Works alongside {@link RateLimiter} to provide IP-based throttling.
 * Useful for:
 * <ul>
 *   <li>Limiting unauthenticated requests</li>
 *   <li>Additional protection layer beyond API key limits</li>
 *   <li>DDoS protection</li>
 * </ul>
 * 
 * <p>Uses sliding window counters with configurable limits.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class IpRateLimiter {
    
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    
    private final int requestsPerMinute;
    private final int requestsPerHour;
    private final boolean enabled;
    
    /**
     * Creates a new IP rate limiter with specified limits.
     * 
     * @param requestsPerMinute maximum requests per minute per IP (0 = unlimited)
     * @param requestsPerHour maximum requests per hour per IP (0 = unlimited)
     * @param enabled whether IP rate limiting is enabled
     */
    public IpRateLimiter(int requestsPerMinute, int requestsPerHour, boolean enabled) {
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerHour = requestsPerHour;
        this.enabled = enabled;
    }
    
    /**
     * Checks if a request from the given IP is allowed.
     * 
     * @param ipAddress the client IP address
     * @return result containing allowance status
     */
    public IpRateLimitResult checkAndIncrement(String ipAddress) {
        Objects.requireNonNull(ipAddress, "ipAddress cannot be null");
        
        if (!enabled) {
            return IpRateLimitResult.allowed();
        }
        
        if (isInternalIp(ipAddress)) {
            return IpRateLimitResult.allowed();
        }
        
        long now = Instant.now().getEpochSecond();
        
        WindowStatus minuteStatus = checkWindow(ipAddress, TimeWindow.MINUTE, requestsPerMinute, now);
        WindowStatus hourStatus = checkWindow(ipAddress, TimeWindow.HOUR, requestsPerHour, now);
        
        boolean allAllowed = minuteStatus.allowed && hourStatus.allowed;
        
        if (!allAllowed) {
            String reason = buildRejectionReason(minuteStatus, hourStatus);
            return IpRateLimitResult.rejected(reason, minuteStatus, hourStatus);
        }
        
        WindowStatus minuteUpdated = incrementWindow(ipAddress, TimeWindow.MINUTE, requestsPerMinute, now);
        WindowStatus hourUpdated = incrementWindow(ipAddress, TimeWindow.HOUR, requestsPerHour, now);
        
        return IpRateLimitResult.allowed(minuteUpdated, hourUpdated);
    }
    
    public IpQuotaStatus getQuotaStatus(String ipAddress) {
        Objects.requireNonNull(ipAddress, "ipAddress cannot be null");
        
        if (!enabled) {
            return IpQuotaStatus.unlimited();
        }
        
        long now = Instant.now().getEpochSecond();
        
        WindowStatus minuteStatus = checkWindow(ipAddress, TimeWindow.MINUTE, requestsPerMinute, now);
        WindowStatus hourStatus = checkWindow(ipAddress, TimeWindow.HOUR, requestsPerHour, now);
        
        return new IpQuotaStatus(ipAddress, minuteStatus, hourStatus, Instant.now());
    }
    
    public void reset(String ipAddress) {
        counters.keySet().removeIf(key -> key.startsWith(ipAddress + ":"));
    }
    
    private boolean isInternalIp(String ip) {
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) {
            if (ip.startsWith("172.")) {
                try {
                    int secondOctet = Integer.parseInt(ip.split("\\.")[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
    
    private WindowStatus checkWindow(String ipAddress, TimeWindow window, int limit, long now) {
        if (limit <= 0) {
            return WindowStatus.allowed(window.windowName(), Long.MAX_VALUE, 0, window.currentWindowReset());
        }
        
        String key = buildKey(ipAddress, window);
        long windowStart = window.currentWindowStart();
        long windowReset = window.currentWindowReset();
        
        WindowCounter counter = counters.get(key);
        
        if (counter == null || counter.windowStart() != windowStart) {
            return WindowStatus.allowed(window.windowName(), limit, limit, windowReset);
        }
        
        long remaining = Math.max(0, limit - counter.count());
        boolean allowed = remaining > 0;
        
        if (allowed) {
            return WindowStatus.allowed(window.windowName(), remaining, limit, windowReset);
        } else {
            return WindowStatus.rejected(window.windowName(), 0, limit, windowReset);
        }
    }
    
    private WindowStatus incrementWindow(String ipAddress, TimeWindow window, int limit, long now) {
        if (limit <= 0) {
            return WindowStatus.allowed(window.windowName(), Long.MAX_VALUE, 0, window.currentWindowReset());
        }
        
        String key = buildKey(ipAddress, window);
        long windowStart = window.currentWindowStart();
        long windowReset = window.currentWindowReset();
        
        WindowCounter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.windowStart() != windowStart) {
                return new WindowCounter(windowStart, 1);
            }
            return v.increment();
        });
        
        long remaining = Math.max(0, limit - counter.count());
        return WindowStatus.allowed(window.windowName(), remaining, limit, windowReset);
    }
    
    private String buildKey(String ipAddress, TimeWindow window) {
        return ipAddress + ":" + window.name();
    }
    
    private String buildRejectionReason(WindowStatus minute, WindowStatus hour) {
        if (!minute.allowed) {
            return String.format("Per-minute IP rate limit exceeded. Resets at %s", 
                Instant.ofEpochSecond(minute.resetAt));
        }
        return String.format("Per-hour IP rate limit exceeded. Resets at %s", 
            Instant.ofEpochSecond(hour.resetAt));
    }
    
    public record IpRateLimitResult(
        boolean isAllowed,
        WindowStatus minuteWindow,
        WindowStatus hourWindow,
        String rejectionReason
    ) {
        public static IpRateLimitResult allowed() {
            long farFuture = Instant.now().plusSeconds(86400 * 365).getEpochSecond();
            return new IpRateLimitResult(true, 
                WindowStatus.allowed("minute", Long.MAX_VALUE, 0, farFuture),
                WindowStatus.allowed("hour", Long.MAX_VALUE, 0, farFuture),
                null);
        }
        
        public static IpRateLimitResult allowed(WindowStatus minute, WindowStatus hour) {
            return new IpRateLimitResult(true, minute, hour, null);
        }
        
        public static IpRateLimitResult rejected(String reason, WindowStatus minute, WindowStatus hour) {
            return new IpRateLimitResult(false, minute, hour, reason);
        }
    }
    
    public record WindowStatus(
        String window,
        boolean allowed,
        long remaining,
        long limit,
        long resetAt,
        double usagePercent
    ) {
        public static WindowStatus allowed(String window, long remaining, long limit, long resetAt) {
            double usage = limit > 0 ? ((double) (limit - remaining) / limit) * 100 : 0;
            return new WindowStatus(window, true, remaining, limit, resetAt, usage);
        }
        
        public static WindowStatus rejected(String window, long remaining, long limit, long resetAt) {
            double usage = limit > 0 ? ((double) (limit - remaining) / limit) * 100 : 100;
            return new WindowStatus(window, false, remaining, limit, resetAt, usage);
        }
    }
    
    public record IpQuotaStatus(
        String ipAddress,
        WindowStatus minuteWindow,
        WindowStatus hourWindow,
        Instant checkedAt
    ) {
        public static IpQuotaStatus unlimited() {
            long farFuture = Instant.now().plusSeconds(86400 * 365).getEpochSecond();
            return new IpQuotaStatus(
                "unlimited",
                WindowStatus.allowed("minute", Long.MAX_VALUE, 0, farFuture),
                WindowStatus.allowed("hour", Long.MAX_VALUE, 0, farFuture),
                Instant.now()
            );
        }
        
        public boolean isExhausted() {
            return !minuteWindow.allowed || !hourWindow.allowed;
        }
    }
    
    enum TimeWindow {
        MINUTE(60, "minute"),
        HOUR(3600, "hour");
        
        private final int seconds;
        private final String windowName;
        
        TimeWindow(int seconds, String windowName) {
            this.seconds = seconds;
            this.windowName = windowName;
        }
        
        public int seconds() { return seconds; }
        public String windowName() { return windowName; }
        
        public long currentWindowStart() {
            long now = Instant.now().getEpochSecond();
            return (now / seconds) * seconds;
        }
        
        public long currentWindowReset() {
            return currentWindowStart() + seconds;
        }
    }
    
    private record WindowCounter(long windowStart, long count) {
        WindowCounter {
            if (windowStart <= 0) {
                throw new IllegalArgumentException("windowStart must be positive");
            }
            if (count < 0) {
                throw new IllegalArgumentException("count cannot be negative");
            }
        }
        
        WindowCounter increment() {
            return new WindowCounter(windowStart, count + 1);
        }
    }
    
    public void clear() {
        counters.clear();
    }
    
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getRequestsPerHour() { return requestsPerHour; }
    public boolean isEnabled() { return enabled; }
}
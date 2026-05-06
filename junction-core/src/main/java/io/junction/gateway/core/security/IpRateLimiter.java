package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>Uses fixed window counters with configurable limits.
 *
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class IpRateLimiter {
    
    private static final int DEFAULT_MAX_IP_STATES = 100_000;
    private static final long CLEANUP_INTERVAL_REQUESTS = 1024;
    
    private final Map<String, IpRateLimitState> counters = new ConcurrentHashMap<>();
    private final AtomicLong cleanupCounter = new AtomicLong();
    private final Object admissionLock = new Object();
    
    private final int requestsPerMinute;
    private final int requestsPerHour;
    private final boolean enabled;
    private final int maxIpStates;
    private final long stateTtlSeconds;
    
    /**
     * Creates a new IP rate limiter with specified limits.
     *
     * @param requestsPerMinute maximum requests per minute per IP (0 = unlimited)
     * @param requestsPerHour maximum requests per hour per IP (0 = unlimited)
     * @param enabled whether IP rate limiting is enabled
     */
    public IpRateLimiter(int requestsPerMinute, int requestsPerHour, boolean enabled) {
        this(requestsPerMinute, requestsPerHour, enabled, DEFAULT_MAX_IP_STATES);
    }
    
    /**
     * Creates a new IP rate limiter with specified limits and bounded state.
     *
     * @param requestsPerMinute maximum requests per minute per IP (0 = unlimited)
     * @param requestsPerHour maximum requests per hour per IP (0 = unlimited)
     * @param enabled whether IP rate limiting is enabled
     * @param maxIpStates maximum distinct public IPs to track before rejecting new IPs
     */
    public IpRateLimiter(int requestsPerMinute, int requestsPerHour, boolean enabled, int maxIpStates) {
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerHour = requestsPerHour;
        this.enabled = enabled;
        this.maxIpStates = Math.max(1, maxIpStates);
        this.stateTtlSeconds = computeTtlSeconds(requestsPerMinute, requestsPerHour);
    }
    
    /**
     * Checks if a request from the given IP is allowed.
     *
     * @param ipAddress the client IP address
     * @return result containing allowance status
     */
    public IpRateLimitResult checkAndIncrement(String ipAddress) {
        Objects.requireNonNull(ipAddress, "ipAddress cannot be null");
        
        if (!enabled || limitsUnlimited()) {
            return IpRateLimitResult.allowed();
        }
        
        if (isInternalIp(ipAddress)) {
            return IpRateLimitResult.allowed();
        }
        
        long now = Instant.now().getEpochSecond();
        cleanupExpiredIfNeeded(now);
        
        if (!admitIpState(ipAddress, now)) {
            return capacityRejected(now);
        }
        
        var result = new AtomicReference<IpRateLimitResult>();
        counters.compute(ipAddress, (key, state) -> {
            IpRateLimitState current = normalizeState(state, now);
            
            WindowStatus minuteStatus = status(current.minute(), TimeWindow.MINUTE, requestsPerMinute, now);
            WindowStatus hourStatus = status(current.hour(), TimeWindow.HOUR, requestsPerHour, now);
            
            if (!minuteStatus.allowed || !hourStatus.allowed) {
                String reason = buildRejectionReason(minuteStatus, hourStatus);
                result.set(IpRateLimitResult.rejected(reason, minuteStatus, hourStatus));
                return current.withLastAccess(now);
            }
            
            IpRateLimitState updated = new IpRateLimitState(
                requestsPerMinute > 0 ? current.minute().increment() : current.minute(),
                requestsPerHour > 0 ? current.hour().increment() : current.hour(),
                now
            );
            
            WindowStatus minuteUpdated = status(updated.minute(), TimeWindow.MINUTE, requestsPerMinute, now);
            WindowStatus hourUpdated = status(updated.hour(), TimeWindow.HOUR, requestsPerHour, now);
            result.set(IpRateLimitResult.allowed(minuteUpdated, hourUpdated));
            return updated;
        });
        
        return result.get();
    }
    
    public IpQuotaStatus getQuotaStatus(String ipAddress) {
        Objects.requireNonNull(ipAddress, "ipAddress cannot be null");
        
        if (!enabled || limitsUnlimited()) {
            return IpQuotaStatus.unlimited();
        }
        
        long now = Instant.now().getEpochSecond();
        IpRateLimitState state = normalizeState(counters.get(ipAddress), now);
        
        WindowStatus minuteStatus = status(state.minute(), TimeWindow.MINUTE, requestsPerMinute, now);
        WindowStatus hourStatus = status(state.hour(), TimeWindow.HOUR, requestsPerHour, now);
        
        return new IpQuotaStatus(ipAddress, minuteStatus, hourStatus, Instant.now());
    }
    
    public void reset(String ipAddress) {
        counters.remove(ipAddress);
    }
    
    private boolean limitsUnlimited() {
        return requestsPerMinute <= 0 && requestsPerHour <= 0;
    }
    
    private long computeTtlSeconds(int requestsPerMinute, int requestsPerHour) {
        long longest = 0;
        if (requestsPerMinute > 0) {
            longest = Math.max(longest, TimeWindow.MINUTE.seconds());
        }
        if (requestsPerHour > 0) {
            longest = Math.max(longest, TimeWindow.HOUR.seconds());
        }
        return longest == 0 ? 0 : longest * 2;
    }
    
    private void cleanupExpiredIfNeeded(long now) {
        if (stateTtlSeconds <= 0) {
            return;
        }
        if (cleanupCounter.incrementAndGet() % CLEANUP_INTERVAL_REQUESTS != 0) {
            return;
        }
        cleanupExpired(now);
    }
    
    private void cleanupExpired(long now) {
        if (stateTtlSeconds <= 0) {
            return;
        }
        long expireBefore = now - stateTtlSeconds;
        counters.entrySet().removeIf(entry -> entry.getValue().lastAccessEpochSecond() < expireBefore);
    }
    
    private boolean admitIpState(String ipAddress, long now) {
        if (counters.containsKey(ipAddress)) {
            return true;
        }
        synchronized (admissionLock) {
            if (counters.containsKey(ipAddress)) {
                return true;
            }
            if (counters.size() >= maxIpStates) {
                cleanupExpired(now);
            }
            if (counters.size() >= maxIpStates) {
                return false;
            }
            counters.put(ipAddress, new IpRateLimitState(
                normalizeCounter(null, TimeWindow.MINUTE, now),
                normalizeCounter(null, TimeWindow.HOUR, now),
                now
            ));
            return true;
        }
    }

    private IpRateLimitResult capacityRejected(long now) {
        long minuteReset = currentWindowReset(TimeWindow.MINUTE, now);
        long hourReset = currentWindowReset(TimeWindow.HOUR, now);
        var minute = WindowStatus.rejected("minute", 0, requestsPerMinute, minuteReset);
        var hour = WindowStatus.rejected("hour", 0, requestsPerHour, hourReset);
        return IpRateLimitResult.rejected("IP rate limit state capacity exceeded", minute, hour);
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
    
    private IpRateLimitState normalizeState(IpRateLimitState state, long now) {
        return new IpRateLimitState(
            normalizeCounter(state == null ? null : state.minute(), TimeWindow.MINUTE, now),
            normalizeCounter(state == null ? null : state.hour(), TimeWindow.HOUR, now),
            state == null ? now : state.lastAccessEpochSecond()
        );
    }
    
    private WindowCounter normalizeCounter(WindowCounter counter, TimeWindow window, long now) {
        long windowStart = currentWindowStart(window, now);
        if (counter == null || counter.windowStart() != windowStart) {
            return new WindowCounter(windowStart, 0);
        }
        return counter;
    }
    
    private WindowStatus status(WindowCounter counter, TimeWindow window, int limit, long now) {
        if (limit <= 0) {
            return WindowStatus.allowed(window.windowName(), Long.MAX_VALUE, 0, currentWindowReset(window, now));
        }
        
        long remaining = Math.max(0, limit - counter.count());
        boolean allowed = remaining > 0;
        
        if (allowed) {
            return WindowStatus.allowed(window.windowName(), remaining, limit, currentWindowReset(window, now));
        }
        return WindowStatus.rejected(window.windowName(), 0, limit, currentWindowReset(window, now));
    }
    
    private long currentWindowStart(TimeWindow window, long now) {
        return (now / window.seconds()) * window.seconds();
    }
    
    private long currentWindowReset(TimeWindow window, long now) {
        return currentWindowStart(window, now) + window.seconds();
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
    
    private record IpRateLimitState(WindowCounter minute, WindowCounter hour, long lastAccessEpochSecond) {
        IpRateLimitState withLastAccess(long now) {
            return new IpRateLimitState(minute, hour, now);
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
    
    public int getStateSize() {
        return counters.size();
    }
    
    public long getCount(String ipAddress, TimeWindow window) {
        IpRateLimitState state = counters.get(ipAddress);
        if (state == null) {
            return 0;
        }
        return switch (window) {
            case MINUTE -> state.minute().count();
            case HOUR -> state.hour().count();
        };
    }
    
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getRequestsPerHour() { return requestsPerHour; }
    public boolean isEnabled() { return enabled; }
    public int getMaxIpStates() { return maxIpStates; }
}

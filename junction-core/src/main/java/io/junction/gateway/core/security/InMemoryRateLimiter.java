package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link RateLimiter}.
 * 
 * <p>Uses ConcurrentHashMap for thread-safe rate limit tracking.
 * Suitable for single-node deployments.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class InMemoryRateLimiter implements RateLimiter {
    
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    
    @Override
    public RateLimitResult checkAndIncrement(String apiKeyId, ApiKey.Tier tier) {
        Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        
        long now = Instant.now().getEpochSecond();
        
        WindowStatus minuteStatus = checkWindow(apiKeyId, TimeWindow.MINUTE, tier.requestsPerMinute(), now);
        WindowStatus dayStatus = checkWindow(apiKeyId, TimeWindow.DAY, tier.requestsPerDay(), now);
        WindowStatus monthStatus = checkWindow(apiKeyId, TimeWindow.MONTH, tier.requestsPerMonth(), now);
        
        boolean allAllowed = minuteStatus.allowed() && dayStatus.allowed() && monthStatus.allowed();
        
        if (!allAllowed) {
            String reason = buildRejectionReason(minuteStatus, dayStatus, monthStatus);
            return RateLimitResult.rejected(reason, minuteStatus, dayStatus, monthStatus);
        }
        
        WindowStatus minuteUpdated = incrementWindow(apiKeyId, TimeWindow.MINUTE, tier.requestsPerMinute(), now);
        WindowStatus dayUpdated = incrementWindow(apiKeyId, TimeWindow.DAY, tier.requestsPerDay(), now);
        WindowStatus monthUpdated = incrementWindow(apiKeyId, TimeWindow.MONTH, tier.requestsPerMonth(), now);
        
        return RateLimitResult.allowed(minuteUpdated, dayUpdated, monthUpdated);
    }
    
    @Override
    public QuotaStatus getQuotaStatus(String apiKeyId, ApiKey.Tier tier) {
        Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        
        long now = Instant.now().getEpochSecond();
        
        WindowStatus minuteStatus = checkWindow(apiKeyId, TimeWindow.MINUTE, tier.requestsPerMinute(), now);
        WindowStatus dayStatus = checkWindow(apiKeyId, TimeWindow.DAY, tier.requestsPerDay(), now);
        WindowStatus monthStatus = checkWindow(apiKeyId, TimeWindow.MONTH, tier.requestsPerMonth(), now);
        
        return new QuotaStatus(
            apiKeyId,
            tier,
            minuteStatus,
            dayStatus,
            monthStatus,
            Instant.now()
        );
    }
    
    @Override
    public void reset(String apiKeyId) {
        counters.keySet().removeIf(key -> key.startsWith(apiKeyId + ":"));
    }
    
    private WindowStatus checkWindow(String apiKeyId, TimeWindow window, int limit, long now) {
        String key = buildKey(apiKeyId, window);
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
    
    private WindowStatus incrementWindow(String apiKeyId, TimeWindow window, int limit, long now) {
        String key = buildKey(apiKeyId, window);
        long windowStart = window.currentWindowStart();
        long windowReset = window.currentWindowReset();
        
        WindowCounter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.windowStart() != windowStart) {
                return new WindowCounter(windowStart, 1);
            }
            return v.increment();
        });
        
        long remaining = Math.max(0, limit - counter.count());
        boolean allowed = remaining >= 0;
        
        return WindowStatus.allowed(window.windowName(), remaining, limit, windowReset);
    }
    
    private String buildKey(String apiKeyId, TimeWindow window) {
        return apiKeyId + ":" + window.name();
    }
    
    private String buildRejectionReason(WindowStatus minute, WindowStatus day, WindowStatus month) {
        if (!minute.allowed()) {
            return String.format("Per-minute rate limit exceeded. Resets at %s", 
                Instant.ofEpochSecond(minute.resetAt()));
        }
        if (!day.allowed()) {
            return String.format("Daily rate limit exceeded. Resets at %s", 
                Instant.ofEpochSecond(day.resetAt()));
        }
        return String.format("Monthly rate limit exceeded. Resets at %s", 
            Instant.ofEpochSecond(month.resetAt()));
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
    
    public long getCount(String apiKeyId, TimeWindow window) {
        String key = buildKey(apiKeyId, window);
        WindowCounter counter = counters.get(key);
        return counter != null ? counter.count() : 0;
    }
}

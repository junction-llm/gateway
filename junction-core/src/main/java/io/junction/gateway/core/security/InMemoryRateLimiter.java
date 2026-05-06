package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    
    private final Map<String, RateLimitState> counters = new ConcurrentHashMap<>();
    
    @Override
    public RateLimitResult checkAndIncrement(String apiKeyId, ApiKey.Tier tier) {
        Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        
        long now = Instant.now().getEpochSecond();
        var result = new AtomicReference<RateLimitResult>();
        
        counters.compute(apiKeyId, (key, state) -> {
            RateLimitState current = normalizeState(state, now);
            
            WindowStatus minuteStatus = status(current.minute(), TimeWindow.MINUTE, tier.requestsPerMinute(), now);
            WindowStatus dayStatus = status(current.day(), TimeWindow.DAY, tier.requestsPerDay(), now);
            WindowStatus monthStatus = status(current.month(), TimeWindow.MONTH, tier.requestsPerMonth(), now);
            
            if (!minuteStatus.allowed() || !dayStatus.allowed() || !monthStatus.allowed()) {
                String reason = buildRejectionReason(minuteStatus, dayStatus, monthStatus);
                result.set(RateLimitResult.rejected(reason, minuteStatus, dayStatus, monthStatus));
                return current;
            }
            
            RateLimitState updated = new RateLimitState(
                current.minute().increment(),
                current.day().increment(),
                current.month().increment()
            );
            
            WindowStatus minuteUpdated = status(updated.minute(), TimeWindow.MINUTE, tier.requestsPerMinute(), now);
            WindowStatus dayUpdated = status(updated.day(), TimeWindow.DAY, tier.requestsPerDay(), now);
            WindowStatus monthUpdated = status(updated.month(), TimeWindow.MONTH, tier.requestsPerMonth(), now);
            result.set(RateLimitResult.allowed(minuteUpdated, dayUpdated, monthUpdated));
            return updated;
        });
        
        return result.get();
    }
    
    @Override
    public QuotaStatus getQuotaStatus(String apiKeyId, ApiKey.Tier tier) {
        Objects.requireNonNull(apiKeyId, "apiKeyId cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        
        long now = Instant.now().getEpochSecond();
        RateLimitState state = normalizeState(counters.get(apiKeyId), now);
        
        WindowStatus minuteStatus = status(state.minute(), TimeWindow.MINUTE, tier.requestsPerMinute(), now);
        WindowStatus dayStatus = status(state.day(), TimeWindow.DAY, tier.requestsPerDay(), now);
        WindowStatus monthStatus = status(state.month(), TimeWindow.MONTH, tier.requestsPerMonth(), now);
        
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
        counters.remove(apiKeyId);
    }
    
    private RateLimitState normalizeState(RateLimitState state, long now) {
        return new RateLimitState(
            normalizeCounter(state == null ? null : state.minute(), TimeWindow.MINUTE, now),
            normalizeCounter(state == null ? null : state.day(), TimeWindow.DAY, now),
            normalizeCounter(state == null ? null : state.month(), TimeWindow.MONTH, now)
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
        long remaining = Math.max(0, limit - counter.count());
        long windowReset = currentWindowReset(window, now);
        boolean allowed = remaining > 0;
        
        if (allowed) {
            return WindowStatus.allowed(window.windowName(), remaining, limit, windowReset);
        }
        return WindowStatus.rejected(window.windowName(), 0, limit, windowReset);
    }
    
    private long currentWindowStart(TimeWindow window, long now) {
        return (now / window.seconds()) * window.seconds();
    }
    
    private long currentWindowReset(TimeWindow window, long now) {
        return currentWindowStart(window, now) + window.seconds();
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
    
    private record RateLimitState(WindowCounter minute, WindowCounter day, WindowCounter month) {
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
        RateLimitState state = counters.get(apiKeyId);
        if (state == null) {
            return 0;
        }
        return switch (window) {
            case MINUTE -> state.minute().count();
            case DAY -> state.day().count();
            case MONTH -> state.month().count();
        };
    }
}

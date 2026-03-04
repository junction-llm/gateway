package io.junction.gateway.core.exception;

import io.junction.gateway.core.security.IpRateLimiter;

/**
 * Exception thrown when IP rate limit is exceeded.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class IpRateLimitExceededException extends RuntimeException {
    
    private final String ipAddress;
    private final IpRateLimiter.IpRateLimitResult rateLimitResult;
    
    public IpRateLimitExceededException(String ipAddress, IpRateLimiter.IpRateLimitResult rateLimitResult) {
        super(rateLimitResult.rejectionReason());
        this.ipAddress = ipAddress;
        this.rateLimitResult = rateLimitResult;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public IpRateLimiter.IpRateLimitResult getRateLimitResult() {
        return rateLimitResult;
    }
    
    public long getRetryAfter() {
        if (!rateLimitResult.minuteWindow().allowed()) {
            return rateLimitResult.minuteWindow().resetAt();
        }
        return rateLimitResult.hourWindow().resetAt();
    }
}

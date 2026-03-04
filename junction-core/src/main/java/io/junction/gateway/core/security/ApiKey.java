package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable API Key entity representing a client API key.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public final class ApiKey {
    
    private final String id;
    private final String keyHash;
    private final String keyPrefix;
    private final String name;
    private final String description;
    private final Tier tier;
    private final Status status;
    private final Set<String> allowedModels;
    private final Set<String> allowedIps;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant lastUsedAt;
    private final long requestCount;
    private final String createdBy;
    private final Metadata metadata;
    
    private ApiKey(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.keyHash = Objects.requireNonNull(builder.keyHash, "keyHash cannot be null");
        this.keyPrefix = Objects.requireNonNull(builder.keyPrefix, "keyPrefix cannot be null");
        this.name = builder.name;
        this.description = builder.description;
        this.tier = Objects.requireNonNull(builder.tier, "tier cannot be null");
        this.status = Objects.requireNonNull(builder.status, "status cannot be null");
        this.allowedModels = Set.copyOf(builder.allowedModels);
        this.allowedIps = Set.copyOf(builder.allowedIps);
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt cannot be null");
        this.expiresAt = builder.expiresAt;
        this.lastUsedAt = builder.lastUsedAt;
        this.requestCount = builder.requestCount;
        this.createdBy = builder.createdBy;
        this.metadata = builder.metadata != null ? builder.metadata : new Metadata();
    }
    
    public static ApiKey createNew(String keyHash, String keyPrefix, String name, Tier tier) {
        return new Builder()
            .id(java.util.UUID.randomUUID().toString())
            .keyHash(keyHash)
            .keyPrefix(keyPrefix)
            .name(name)
            .tier(tier)
            .status(Status.ACTIVE)
            .createdAt(Instant.now())
            .build();
    }
    
    public String id() { return id; }
    public String keyHash() { return keyHash; }
    public String keyPrefix() { return keyPrefix; }
    public String name() { return name; }
    public String description() { return description; }
    public Tier tier() { return tier; }
    public Status status() { return status; }
    public Set<String> allowedModels() { return allowedModels; }
    public Set<String> allowedIps() { return allowedIps; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public long requestCount() { return requestCount; }
    public String createdBy() { return createdBy; }
    public Metadata metadata() { return metadata; }
    
    public boolean isActive() {
        if (status != Status.ACTIVE) {
            return false;
        }
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }
    
    public ApiKey withUsageUpdate() {
        return new Builder(this)
            .lastUsedAt(Instant.now())
            .requestCount(this.requestCount + 1)
            .build();
    }
    
    public ApiKey revoked() {
        return new Builder(this)
            .status(Status.REVOKED)
            .build();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiKey apiKey)) return false;
        return Objects.equals(id, apiKey.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return String.format("ApiKey{id='%s', name='%s', tier=%s, status=%s}", 
            id, name, tier, status);
    }
    
    public enum Tier {
        FREE(20, 200, 1000),
        
        PRO(100, 5000, 50000),
        
        ENTERPRISE(1000, 50000, 500000);
        
        private final int requestsPerMinute;
        private final int requestsPerDay;
        private final int requestsPerMonth;
        
        Tier(int requestsPerMinute, int requestsPerDay, int requestsPerMonth) {
            this.requestsPerMinute = requestsPerMinute;
            this.requestsPerDay = requestsPerDay;
            this.requestsPerMonth = requestsPerMonth;
        }
        
        public int requestsPerMinute() { return requestsPerMinute; }
        public int requestsPerDay() { return requestsPerDay; }
        public int requestsPerMonth() { return requestsPerMonth; }
    }
    
    public enum Status {
        ACTIVE,
        SUSPENDED,
        REVOKED,
        EXPIRED
    }
    
    public record Metadata(
        String organization,
        String contactEmail,
        String webhookUrl,
        java.util.Map<String, String> customFields
    ) {
        public Metadata() {
            this(null, null, null, java.util.Map.of());
        }
        
        public Metadata {
            customFields = customFields != null ? Map.copyOf(customFields) : Map.of();
        }
    }
    
    public static class Builder {
        private String id;
        private String keyHash;
        private String keyPrefix;
        private String name;
        private String description;
        private Tier tier = Tier.FREE;
        private Status status = Status.ACTIVE;
        private Set<String> allowedModels = Set.of();
        private Set<String> allowedIps = Set.of();
        private Instant createdAt;
        private Instant expiresAt;
        private Instant lastUsedAt;
        private long requestCount = 0;
        private String createdBy;
        private Metadata metadata;
        
        public Builder() {}
        
        public Builder(ApiKey existing) {
            this.id = existing.id;
            this.keyHash = existing.keyHash;
            this.keyPrefix = existing.keyPrefix;
            this.name = existing.name;
            this.description = existing.description;
            this.tier = existing.tier;
            this.status = existing.status;
            this.allowedModels = existing.allowedModels;
            this.allowedIps = existing.allowedIps;
            this.createdAt = existing.createdAt;
            this.expiresAt = existing.expiresAt;
            this.lastUsedAt = existing.lastUsedAt;
            this.requestCount = existing.requestCount;
            this.createdBy = existing.createdBy;
            this.metadata = existing.metadata;
        }
        
        public Builder id(String id) { this.id = id; return this; }
        public Builder keyHash(String keyHash) { this.keyHash = keyHash; return this; }
        public Builder keyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder tier(Tier tier) { this.tier = tier; return this; }
        public Builder status(Status status) { this.status = status; return this; }
        public Builder allowedModels(Set<String> allowedModels) { this.allowedModels = allowedModels; return this; }
        public Builder allowedIps(Set<String> allowedIps) { this.allowedIps = allowedIps; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder lastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; return this; }
        public Builder requestCount(long requestCount) { this.requestCount = requestCount; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder metadata(Metadata metadata) { this.metadata = metadata; return this; }
        
        public ApiKey build() {
            return new ApiKey(this);
        }
    }
}

package io.junction.gateway.core.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service for validating API keys and enforcing security policies.
 * 
 * <p>This service orchestrates:
 * <ul>
 *   <li>API key format validation</li>
 *   <li>Key hash verification</li>
 *   <li>Status and expiration checks</li>
 *   <li>IP allowlist validation</li>
 *   <li>Model access control</li>
 *   <li>Rate limiting</li>
 * </ul>
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class ApiKeyValidator {
    
    private static final String KEY_PREFIX = "junc_";
    private static final int KEY_MIN_LENGTH = 32;
    private static final int KEY_MAX_LENGTH = 128;
    
    private final ApiKeyRepository repository;
    private final RateLimiter rateLimiter;
    private final boolean requireApiKey;
    
    public ApiKeyValidator(ApiKeyRepository repository, RateLimiter rateLimiter, boolean requireApiKey) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter cannot be null");
        this.requireApiKey = requireApiKey;
    }
    
    /**
     * Validates an API key for a request.
     * 
     * @param rawKey the raw API key from the request header
     * @param clientIp the client IP address
     * @param requestedModel the model being requested (optional)
     * @return validation result
     */
    public ValidationResult validate(String rawKey, String clientIp, String requestedModel) {
        // Check if API key is required
        if (!requireApiKey) {
            return ValidationResult.success(
                null,
                ApiKey.Tier.ENTERPRISE, // Unlimited when no key required
                RateLimiter.QuotaStatus.unlimited()
            );
        }
        
        // Check if key is present
        if (rawKey == null || rawKey.isBlank()) {
            return ValidationResult.failure(
                ValidationError.MISSING_KEY,
                "API key is required. Provide it in the X-API-Key header or Authorization: Bearer header."
            );
        }
        
        // Validate key format
        ValidationResult formatResult = validateFormat(rawKey);
        if (!formatResult.valid()) {
            return formatResult;
        }
        
        // Extract prefix for lookup
        String prefix = extractPrefix(rawKey);
        
        // Find key by prefix
        Optional<ApiKey> keyOpt = repository.findByKeyPrefix(prefix);
        if (keyOpt.isEmpty()) {
            return ValidationResult.failure(
                ValidationError.INVALID_KEY,
                "Invalid API key."
            );
        }
        
        ApiKey apiKey = keyOpt.get();
        
        // Verify full key hash
        String providedHash = hashKey(rawKey);
        if (!apiKey.keyHash().equals(providedHash)) {
            return ValidationResult.failure(
                ValidationError.INVALID_KEY,
                "Invalid API key."
            );
        }
        
        // Check status
        if (!apiKey.isActive()) {
            return switch (apiKey.status()) {
                case SUSPENDED -> ValidationResult.failure(
                    ValidationError.SUSPENDED_KEY,
                    "API key is suspended. Contact support."
                );
                case REVOKED -> ValidationResult.failure(
                    ValidationError.REVOKED_KEY,
                    "API key has been revoked."
                );
                case EXPIRED -> ValidationResult.failure(
                    ValidationError.EXPIRED_KEY,
                    "API key has expired. Please renew your subscription."
                );
                default -> ValidationResult.failure(
                    ValidationError.INACTIVE_KEY,
                    "API key is not active."
                );
            };
        }
        
        // Check IP allowlist
        if (!apiKey.allowedIps().isEmpty() && !apiKey.allowedIps().contains(clientIp)) {
            return ValidationResult.failure(
                ValidationError.IP_NOT_ALLOWED,
                "Access denied from IP: " + clientIp
            );
        }
        
        // Check model access
        if (requestedModel != null && !apiKey.allowedModels().isEmpty()) {
            if (!apiKey.allowedModels().contains(requestedModel)) {
                return ValidationResult.failure(
                    ValidationError.MODEL_NOT_ALLOWED,
                    "Model '" + requestedModel + "' is not allowed for this API key."
                );
            }
        }
        
        // Check rate limits
        RateLimiter.RateLimitResult rateResult = rateLimiter.checkAndIncrement(apiKey.id(), apiKey.tier());
        if (!rateResult.allowed()) {
            RateLimiter.WindowStatus restrictive = rateResult.mostRestrictive();
            return ValidationResult.failure(
                ValidationError.RATE_LIMIT_EXCEEDED,
                String.format("Rate limit exceeded. Window: %s, resets at: %s",
                    restrictive.window(),
                    Instant.ofEpochSecond(restrictive.resetAt())
                ),
                rateResult
            );
        }
        
        // Update usage stats
        repository.incrementUsage(apiKey.id());
        
        return ValidationResult.success(apiKey, apiKey.tier(), rateResult);
    }
    
    /**
     * Validates API key format without checking the database.
     */
    public ValidationResult validateFormat(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return ValidationResult.failure(
                ValidationError.MISSING_KEY,
                "API key is required. Provide it in the X-API-Key header or Authorization: Bearer header."
            );
        }
        
        if (!rawKey.startsWith(KEY_PREFIX)) {
            return ValidationResult.failure(
                ValidationError.INVALID_FORMAT,
                "API key must start with '" + KEY_PREFIX + "'"
            );
        }
        
        String keyPart = rawKey.substring(KEY_PREFIX.length());
        
        if (keyPart.length() < KEY_MIN_LENGTH) {
            return ValidationResult.failure(
                ValidationError.INVALID_FORMAT,
                "API key is too short. Minimum length: " + (KEY_PREFIX.length() + KEY_MIN_LENGTH)
            );
        }
        
        if (keyPart.length() > KEY_MAX_LENGTH) {
            return ValidationResult.failure(
                ValidationError.INVALID_FORMAT,
                "API key is too long. Maximum length: " + (KEY_PREFIX.length() + KEY_MAX_LENGTH)
            );
        }
        
        // Check for valid characters (alphanumeric and underscores)
        if (!keyPart.matches("^[a-zA-Z0-9_]+$")) {
            return ValidationResult.failure(
                ValidationError.INVALID_FORMAT,
                "API key contains invalid characters. Only alphanumeric and underscores allowed."
            );
        }
        
        return ValidationResult.formatValid();
    }
    
    /**
     * Extracts the prefix from a raw API key (first 8 chars after prefix).
     */
    public String extractPrefix(String rawKey) {
        if (rawKey == null || rawKey.length() < KEY_PREFIX.length() + 8) {
            return rawKey;
        }
        return rawKey.substring(0, KEY_PREFIX.length() + 8);
    }
    
    /**
     * Hashes an API key using SHA-256.
     */
    public String hashKey(String rawKey) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Generates a new API key.
     */
    public GeneratedKey generateKey(String name, ApiKey.Tier tier) {
        String rawKey = KEY_PREFIX + generateRandomString(48);
        String keyHash = hashKey(rawKey);
        String prefix = extractPrefix(rawKey);
        
        ApiKey apiKey = ApiKey.createNew(keyHash, prefix, name, tier);
        ApiKey saved = repository.save(apiKey);
        
        return new GeneratedKey(saved.id(), rawKey, saved);
    }
    
    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Result of API key validation.
     */
    public record ValidationResult(
        boolean valid,
        ValidationError error,
        String errorMessage,
        ApiKey apiKey,
        ApiKey.Tier tier,
        RateLimiter.RateLimitResult rateLimitResult,
        RateLimiter.QuotaStatus quotaStatus
    ) {
        public ValidationResult {
            Objects.requireNonNull(tier, "tier cannot be null");
        }
        
        public static ValidationResult success(ApiKey apiKey, ApiKey.Tier tier, RateLimiter.RateLimitResult rateResult) {
            return new ValidationResult(
                true, null, null, apiKey, tier, rateResult, null
            );
        }
        
        public static ValidationResult success(ApiKey apiKey, ApiKey.Tier tier, RateLimiter.QuotaStatus quota) {
            return new ValidationResult(
                true, null, null, apiKey, tier, null, quota
            );
        }
        
        public static ValidationResult failure(ValidationError error, String message) {
            return new ValidationResult(
                false, error, message, null, ApiKey.Tier.FREE, null, null
            );
        }
        
        public static ValidationResult failure(ValidationError error, String message, RateLimiter.RateLimitResult rateResult) {
            return new ValidationResult(
                false, error, message, null, ApiKey.Tier.FREE, rateResult, null
            );
        }
        
        public static ValidationResult formatValid() {
            return new ValidationResult(
                true, null, null, null, ApiKey.Tier.FREE, null, null
            );
        }
        
        /**
         * Gets the HTTP status code for this result.
         */
        public int httpStatus() {
            if (valid) return 200;
            return switch (error) {
                case MISSING_KEY, INVALID_KEY, INVALID_FORMAT -> 401;
                case SUSPENDED_KEY, REVOKED_KEY, EXPIRED_KEY, INACTIVE_KEY -> 403;
                case IP_NOT_ALLOWED, MODEL_NOT_ALLOWED -> 403;
                case RATE_LIMIT_EXCEEDED -> 429;
            };
        }
    }
    
    /**
     * Validation error types.
     */
    public enum ValidationError {
        MISSING_KEY,
        INVALID_KEY,
        INVALID_FORMAT,
        SUSPENDED_KEY,
        REVOKED_KEY,
        EXPIRED_KEY,
        INACTIVE_KEY,
        IP_NOT_ALLOWED,
        MODEL_NOT_ALLOWED,
        RATE_LIMIT_EXCEEDED
    }
    
    /**
     * Generated API key with raw key (shown only once).
     */
    public record GeneratedKey(String id, String rawKey, ApiKey apiKey) {
        public GeneratedKey {
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(rawKey, "rawKey cannot be null");
            Objects.requireNonNull(apiKey, "apiKey cannot be null");
        }
    }
}

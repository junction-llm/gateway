package io.junction.gateway.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiKeyValidator}.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
class ApiKeyValidatorTest {

    private ApiKeyRepository repository;
    private RateLimiter rateLimiter;
    private ApiKeyValidator validator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        rateLimiter = new InMemoryRateLimiter();
        validator = new ApiKeyValidator(repository, rateLimiter, true);
    }

    @Test
    void testValidate_MissingKey() {
        ApiKeyValidator.ValidationResult result = validator.validate(null, "127.0.0.1", null);
        
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.MISSING_KEY, result.error());
        assertEquals(401, result.httpStatus());
        assertEquals(
            "API key is required. Provide it in the X-API-Key header or Authorization: Bearer header.",
            result.errorMessage()
        );
    }

    @Test
    void testValidate_InvalidFormat() {
        ApiKeyValidator.ValidationResult result = validator.validate("invalid_key", "127.0.0.1", null);
        
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.INVALID_FORMAT, result.error());
    }

    @Test
    void testValidate_InvalidKey() {
        ApiKeyValidator.ValidationResult result = validator.validate("junc_abcdefghijklmnopqrstuvwxyz123456789", "127.0.0.1", null);
        
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.INVALID_KEY, result.error());
    }

    @Test
    void testValidate_ValidKey() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("Test Key", ApiKey.Tier.FREE);
        
        ApiKeyValidator.ValidationResult result = validator.validate(generated.rawKey(), "127.0.0.1", null);
        
        assertTrue(result.valid());
        assertNotNull(result.apiKey());
        assertEquals("Test Key", result.apiKey().name());
        assertEquals(ApiKey.Tier.FREE, result.tier());
    }

    @Test
    void testValidate_RateLimitExceeded() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("Test Key", ApiKey.Tier.FREE);
        
        for (int i = 0; i < 20; i++) {
            ApiKeyValidator.ValidationResult result = validator.validate(generated.rawKey(), "127.0.0.1", null);
            assertTrue(result.valid(), "Request " + (i + 1) + " should be allowed");
        }
        
        ApiKeyValidator.ValidationResult result = validator.validate(generated.rawKey(), "127.0.0.1", null);
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.RATE_LIMIT_EXCEEDED, result.error());
        assertEquals(429, result.httpStatus());
    }


    @Test
    void recordsUsageOnlyAfterSuccessfulValidation() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("Recorder Test", ApiKey.Tier.FREE);
        RecordingUsageRecorder recorder = new RecordingUsageRecorder();
        ApiKeyValidator recordingValidator = new ApiKeyValidator(repository, rateLimiter, recorder, true);

        ApiKeyValidator.ValidationResult result = recordingValidator.validate(generated.rawKey(), "127.0.0.1", null);

        assertTrue(result.valid());
        assertEquals(1, recorder.recordedCount());
        assertEquals(generated.id(), recorder.lastRecordedId());
    }

    @Test
    void doesNotRecordUsageForRejectedValidation() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("Rejected Recorder Test", ApiKey.Tier.FREE);
        repository.save(new ApiKey.Builder(generated.apiKey())
            .allowedModels(Set.of("allowed-model"))
            .build());
        RecordingUsageRecorder recorder = new RecordingUsageRecorder();
        ApiKeyValidator recordingValidator = new ApiKeyValidator(repository, rateLimiter, recorder, true);

        recordingValidator.validate(null, "127.0.0.1", null);
        recordingValidator.validate("invalid_key", "127.0.0.1", null);
        recordingValidator.validate("junc_abcdefghijklmnopqrstuvwxyz123456789", "127.0.0.1", null);
        recordingValidator.validate(generated.rawKey(), "203.0.113.1", "forbidden-model");

        assertEquals(0, recorder.recordedCount());
    }

    @Test
    void usageRecorderFailureDoesNotRejectRequest() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("Failing Recorder Test", ApiKey.Tier.FREE);
        ApiKeyUsageRecorder failingRecorder = apiKeyId -> {
            throw new IllegalStateException("recorder unavailable");
        };
        ApiKeyValidator recordingValidator = new ApiKeyValidator(repository, rateLimiter, failingRecorder, true);

        ApiKeyValidator.ValidationResult result = recordingValidator.validate(generated.rawKey(), "127.0.0.1", null);

        assertTrue(result.valid());
    }

    @Test
    void testGenerateKey() {
        ApiKeyValidator.GeneratedKey generated = validator.generateKey("My App", ApiKey.Tier.PRO);
        
        assertNotNull(generated.id());
        assertNotNull(generated.rawKey());
        assertNotNull(generated.apiKey());
        
        assertTrue(generated.rawKey().startsWith("junc_"));
        assertEquals("My App", generated.apiKey().name());
        assertEquals(ApiKey.Tier.PRO, generated.apiKey().tier());
        
        assertTrue(repository.existsByKeyHash(validator.hashKey(generated.rawKey())));
    }

    @Test
    void testHashKey() {
        String key = "junc_test_key_12345";
        String hash1 = validator.hashKey(key);
        String hash2 = validator.hashKey(key);
        
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    void testExtractPrefix() {
        String key = "junc_abcdefghijklmnopqrstuvwxyz";
        String prefix = validator.extractPrefix(key);
        
        assertEquals("junc_abcdefgh", prefix);
    }

    @Test
    void testValidateFormat_Valid() {
        ApiKeyValidator.ValidationResult result = validator.validateFormat("junc_abcdefghijklmnopqrstuvwxyz123456");
        assertTrue(result.valid());
    }

    @Test
    void testValidateFormat_TooShort() {
        ApiKeyValidator.ValidationResult result = validator.validateFormat("junc_short");
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.INVALID_FORMAT, result.error());
    }

    @Test
    void testValidateFormat_WrongPrefix() {
        ApiKeyValidator.ValidationResult result = validator.validateFormat("wrong_abcdefghijklmnopqrstuvwxyz123456");
        assertFalse(result.valid());
        assertEquals(ApiKeyValidator.ValidationError.INVALID_FORMAT, result.error());
    }

    @Test
    void testApiKeyNotRequired() {
        ApiKeyValidator optionalValidator = new ApiKeyValidator(repository, rateLimiter, false);
        
        ApiKeyValidator.ValidationResult result = optionalValidator.validate(null, "127.0.0.1", null);
        
        assertTrue(result.valid());
        assertEquals(ApiKey.Tier.ENTERPRISE, result.tier());
    }


    private static class RecordingUsageRecorder implements ApiKeyUsageRecorder {
        private final AtomicInteger recordedCount = new AtomicInteger();
        private String lastRecordedId;

        @Override
        public void recordUsage(String apiKeyId) {
            recordedCount.incrementAndGet();
            lastRecordedId = apiKeyId;
        }

        int recordedCount() {
            return recordedCount.get();
        }

        String lastRecordedId() {
            return lastRecordedId;
        }
    }
}

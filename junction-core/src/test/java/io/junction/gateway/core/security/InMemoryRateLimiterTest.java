package io.junction.gateway.core.security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Unit tests for {@link InMemoryRateLimiter}.
 *
 * @author Juan Hidalgo
 * @since 0.0.1
 */
class InMemoryRateLimiterTest {
    private InMemoryRateLimiter rateLimiter;
    @BeforeEach
    void setUp() {
        rateLimiter = new InMemoryRateLimiter();
    }
    @Test
    void testCheckAndIncrement_Allowed_UnderLimit() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            var result = rateLimiter.checkAndIncrement("test-client", tier);
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }
    }
    @Test
    void testCheckAndIncrement_Rejected_OverLimit() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("test-client", tier);
        }
        
        var result = rateLimiter.checkAndIncrement("test-client", tier);
        assertFalse(result.allowed(), "21st request should be rejected");
        assertNotNull(result.rejectionReason());
    }
    @Test
    void testDifferentClientsTrackedSeparately() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("client1", tier);
        }
        
        var result1 = rateLimiter.checkAndIncrement("client1", tier);
        assertFalse(result1.allowed(), "client1 should be rate limited after 20 requests");
        
        var result2 = rateLimiter.checkAndIncrement("client2", tier);
        assertTrue(result2.allowed(), "client2 should still have requests available");
    }
    @Test
    void testResetClearsCounter() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("test-client", tier);
        }
        
        assertFalse(rateLimiter.checkAndIncrement("test-client", tier).allowed());
        
        rateLimiter.reset("test-client");
        
        var result = rateLimiter.checkAndIncrement("test-client", tier);
        assertTrue(result.allowed(), "After reset, request should be allowed");
    }
    @Test
    void testMultipleTiersHaveDifferentLimits() {
        
        var freeResult = rateLimiter.checkAndIncrement("free-client", ApiKey.Tier.FREE);
        var proResult = rateLimiter.checkAndIncrement("pro-client", ApiKey.Tier.PRO);
        var enterpriseResult = rateLimiter.checkAndIncrement("enterprise-client", ApiKey.Tier.ENTERPRISE);
        
        assertTrue(freeResult.allowed());
        assertTrue(proResult.allowed());
        assertTrue(enterpriseResult.allowed());
    }
    @Test
    void testQuotaStatusReturnsCorrectValues() {
        
        var tier = ApiKey.Tier.FREE;
        
        var status = rateLimiter.getQuotaStatus("test-client", tier);
        
        assertEquals("test-client", status.apiKeyId());
        assertEquals(tier, status.tier());
        assertTrue(status.minuteWindow().allowed());
        assertEquals(20, status.minuteWindow().limit());
        assertEquals(20, status.minuteWindow().remaining());
    }
    @Test
    void testClearResetsAllCounters() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("client1", tier);
        }
        
        assertFalse(rateLimiter.checkAndIncrement("client1", tier).allowed());
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("client2", tier);
        }
        
        assertFalse(rateLimiter.checkAndIncrement("client2", tier).allowed());
        
        rateLimiter.clear();
        
        assertTrue(rateLimiter.checkAndIncrement("client1", tier).allowed());
        assertTrue(rateLimiter.checkAndIncrement("client2", tier).allowed());
    }
    @Test
    void testFreeTierMinuteLimit() {
        
        var tier = ApiKey.Tier.FREE;
        
        for (int i = 0; i < 20; i++) {
            rateLimiter.checkAndIncrement("test-client", tier);
        }
        
        var result = rateLimiter.checkAndIncrement("test-client", tier);
        assertFalse(result.minuteWindow().allowed());
    }
    @Test
    void testProTierMinuteLimit() {
        
        var tier = ApiKey.Tier.PRO;
        
        for (int i = 0; i < 100; i++) {
            rateLimiter.checkAndIncrement("test-client", tier);
        }
        
        var result = rateLimiter.checkAndIncrement("test-client", tier);
        assertFalse(result.minuteWindow().allowed());
    }
    @Test
    void testEnterpriseTierMinuteLimit() {
        
        var tier = ApiKey.Tier.ENTERPRISE;
        
        for (int i = 0; i < 1000; i++) {
            rateLimiter.checkAndIncrement("test-client", tier);
        }
        
        var result = rateLimiter.checkAndIncrement("test-client", tier);
        assertFalse(result.minuteWindow().allowed());
    }
}
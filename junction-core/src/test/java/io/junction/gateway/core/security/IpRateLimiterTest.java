package io.junction.gateway.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IpRateLimiter}.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
class IpRateLimiterTest {

    private IpRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new IpRateLimiter(2, 20, true);
    }

    

    @Test
    void testCheckAndIncrement_Allowed_UnderLimit() {
        
        String publicIp = "203.0.113.1";

        
        for (int i = 0; i < 2; i++) {
            var result = rateLimiter.checkAndIncrement(publicIp);
            assertTrue(result.isAllowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testCheckAndIncrement_Rejected_OverLimit() {
        
        String publicIp = "203.0.113.1";

        
        for (int i = 0; i < 2; i++) {
            rateLimiter.checkAndIncrement(publicIp);
        }

        
        var result = rateLimiter.checkAndIncrement(publicIp);
        assertFalse(result.isAllowed(), "3rd request should be rejected");
        assertNotNull(result.rejectionReason());
    }

    @Test
    void testDifferentIPsTrackedSeparately() {
        
        String ip1 = "203.0.113.1";
        String ip2 = "203.0.113.2";

        
        for (int i = 0; i < 2; i++) {
            rateLimiter.checkAndIncrement(ip1);
        }

        
        var result1 = rateLimiter.checkAndIncrement(ip1);
        assertFalse(result1.isAllowed(), "client1 should be rate limited");

        
        var result2 = rateLimiter.checkAndIncrement(ip2);
        assertTrue(result2.isAllowed(), "client2 should still have requests available");
    }

    @Test
    void testResetClearsCounter() {
        
        String publicIp = "203.0.113.1";

        
        for (int i = 0; i < 2; i++) {
            rateLimiter.checkAndIncrement(publicIp);
        }
        
        assertFalse(rateLimiter.checkAndIncrement(publicIp).isAllowed());

        
        rateLimiter.reset(publicIp);

        
        var result = rateLimiter.checkAndIncrement(publicIp);
        assertTrue(result.isAllowed(), "After reset, request should be allowed");
    }

    @Test
    void testClearResetsAllCounters() {
        
        String ip1 = "203.0.113.1";
        String ip2 = "203.0.113.2";

        
        for (int i = 0; i < 2; i++) {
            rateLimiter.checkAndIncrement(ip1);
        }
        
        assertFalse(rateLimiter.checkAndIncrement(ip1).isAllowed());

        
        for (int i = 0; i < 2; i++) {
            rateLimiter.checkAndIncrement(ip2);
        }
        
        assertFalse(rateLimiter.checkAndIncrement(ip2).isAllowed());

        
        rateLimiter.clear();

        
        assertTrue(rateLimiter.checkAndIncrement(ip1).isAllowed());
        assertTrue(rateLimiter.checkAndIncrement(ip2).isAllowed());
    }

    

    @Test
    void testDisabledRateLimiter_AllowsAll() {
        
        var disabledLimiter = new IpRateLimiter(10, 100, false);

        
        for (int i = 0; i < 100; i++) {
            var result = disabledLimiter.checkAndIncrement("203.0.113.1");
            assertTrue(result.isAllowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testDisabledRateLimiter_UnlimitedQuota() {
        
        var disabledLimiter = new IpRateLimiter(10, 100, false);

        
        var status = disabledLimiter.getQuotaStatus("203.0.113.1");

        
        assertFalse(status.isExhausted());
    }

    

    @Test
    void testInternalIPsAllowed() {
        
        var result1 = rateLimiter.checkAndIncrement("127.0.0.1");
        assertTrue(result1.isAllowed(), "localhost should be allowed");

        
        var result2 = rateLimiter.checkAndIncrement("::1");
        assertTrue(result2.isAllowed(), "IPv6 localhost should be allowed");

        
        var result3 = rateLimiter.checkAndIncrement("10.0.0.1");
        assertTrue(result3.isAllowed(), "10.x.x.x should be allowed");

        var result4 = rateLimiter.checkAndIncrement("192.168.1.100");
        assertTrue(result4.isAllowed(), "192.168.x.x should be allowed");

        var result5 = rateLimiter.checkAndIncrement("172.16.0.1");
        assertTrue(result5.isAllowed(), "172.16.x.x should be allowed");
    }

    

    @Test
    void testCheckAndIncrement_NullIP_ThrowsException() {
        assertThrows(NullPointerException.class, () -> rateLimiter.checkAndIncrement(null));
    }

    @Test
    void testGetQuotaStatus_NullIP_ThrowsException() {
        assertThrows(NullPointerException.class, () -> rateLimiter.getQuotaStatus(null));
    }

    

    @Test
    void testGetQuotaStatus_ReturnsCorrectValues() {
        
        var limiter = new IpRateLimiter(20, 200, true);

        
        var status = limiter.getQuotaStatus("203.0.113.1");

        
        assertEquals("203.0.113.1", status.ipAddress());
        assertTrue(status.minuteWindow().allowed());
        assertEquals(20, status.minuteWindow().limit());
        assertEquals(20, status.minuteWindow().remaining());
    }

    @Test
    void testHourWindowLimit() {
        
        var limiter = new IpRateLimiter(100, 20, true);

        
        String publicIp = "203.0.113.1";

        
        for (int i = 0; i < 20; i++) {
            limiter.checkAndIncrement(publicIp);
        }

        
        var result = limiter.checkAndIncrement(publicIp);
        assertFalse(result.hourWindow().allowed());
    }

    

    @Test
    void testUnlimitedMinute() {
        
        var limiter = new IpRateLimiter(0, 0, true);

        
        for (int i = 0; i < 1000; i++) {
            var result = limiter.checkAndIncrement("203.0.113.1");
            assertTrue(result.isAllowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void testUnlimitedHour() {
        
        var limiter = new IpRateLimiter(10, 0, true);

        
        for (int i = 0; i < 10; i++) {
            var result = limiter.checkAndIncrement("203.0.113.1");
            assertTrue(result.isAllowed(), "Request " + (i + 1) + " should be allowed");
        }
    }
    @Test
    void checkAndIncrementIsAtomicUnderConcurrency() throws Exception {
        var limiter = new IpRateLimiter(10, 100, true);
        int attempts = 80;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var allowed = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(attempts);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    if (limiter.checkAndIncrement("203.0.113.77").isAllowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(10, allowed.get());
        assertEquals(10, limiter.getCount("203.0.113.77", IpRateLimiter.TimeWindow.MINUTE));
        assertFalse(limiter.checkAndIncrement("203.0.113.77").isAllowed());
    }

    @Test
    void publicIpStateIsBoundedByConfiguredCapacity() {
        var limiter = new IpRateLimiter(10, 100, true, 2);

        assertTrue(limiter.checkAndIncrement("203.0.113.1").isAllowed());
        assertTrue(limiter.checkAndIncrement("203.0.113.2").isAllowed());

        var rejected = limiter.checkAndIncrement("203.0.113.3");

        assertFalse(rejected.isAllowed());
        assertEquals(2, limiter.getStateSize());
        assertEquals("IP rate limit state capacity exceeded", rejected.rejectionReason());
    }

    @Test
    void concurrentNewIpAdmissionDoesNotExceedConfiguredCapacity() throws Exception {
        var limiter = new IpRateLimiter(10, 100, true, 2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(16);
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(16);
        var allowed = new AtomicInteger();

        for (int i = 0; i < 16; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    if (limiter.checkAndIncrement("203.0.113." + (100 + index)).isAllowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(2, allowed.get());
        assertEquals(2, limiter.getStateSize());
    }

    @Test
    void unlimitedAndInternalRequestsDoNotAllocateIpState() {
        var unlimited = new IpRateLimiter(0, 0, true, 2);
        assertTrue(unlimited.checkAndIncrement("203.0.113.1").isAllowed());
        assertEquals(0, unlimited.getStateSize());

        var limiter = new IpRateLimiter(1, 1, true, 2);
        assertTrue(limiter.checkAndIncrement("127.0.0.1").isAllowed());
        assertTrue(limiter.checkAndIncrement("10.0.0.1").isAllowed());
        assertEquals(0, limiter.getStateSize());
    }

}
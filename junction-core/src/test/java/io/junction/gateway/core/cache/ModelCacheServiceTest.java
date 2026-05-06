package io.junction.gateway.core.cache;

import io.junction.gateway.core.model.ModelInfo;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCacheServiceTest {

    @Test
    void recordsTelemetryAndExposesSnapshots() {
        var telemetry = new RecordingTelemetry();
        var cacheService = new ModelCacheService(telemetry);
        var models = List.of(ModelInfo.of("test-model", Map.of("owned_by", "test")));

        cacheService.getModels("ollama", "Ollama", () -> models);
        cacheService.getModels("ollama", "Ollama", List::of);

        assertEquals(List.of("ollama"), telemetry.cacheMisses);
        assertEquals(List.of("ollama"), telemetry.cacheHits);
        assertEquals(1, cacheService.snapshot().size());
        assertEquals("ollama", cacheService.snapshot().getFirst().providerId());

        cacheService.evictCache("ollama");
        cacheService.evictAll();

        assertEquals(List.of("ollama", "all"), telemetry.cacheEvictions);
    }

    @Test
    void concurrentCacheMissesCollapseIntoOneProviderFetch() throws Exception {
        var cacheService = new ModelCacheService();
        var models = List.of(ModelInfo.of("test-model", Map.of("owned_by", "test")));
        int attempts = 50;
        var fetches = new AtomicInteger();
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(attempts);
        var executor = Executors.newFixedThreadPool(attempts);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var result = cacheService.getModels("ollama", "Ollama", () -> {
                        fetches.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return models;
                    });
                    assertEquals(models, result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(1, fetches.get());
        assertEquals(1, cacheService.getCacheSize());
    }

    private static final class RecordingTelemetry implements GatewayTelemetry {
        private final List<String> cacheHits = new ArrayList<>();
        private final List<String> cacheMisses = new ArrayList<>();
        private final List<String> cacheEvictions = new ArrayList<>();

        @Override
        public void recordModelCacheHit(String providerId) {
            cacheHits.add(providerId);
        }

        @Override
        public void recordModelCacheMiss(String providerId) {
            cacheMisses.add(providerId);
        }

        @Override
        public void recordModelCacheEviction(String providerId) {
            cacheEvictions.add(providerId);
        }
    }
}

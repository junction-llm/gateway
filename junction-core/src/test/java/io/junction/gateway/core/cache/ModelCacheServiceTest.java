package io.junction.gateway.core.cache;

import io.junction.gateway.core.model.ModelInfo;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

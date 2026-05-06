package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncApiKeyUsageRecorderTest {

    @Test
    void rejectsInvalidPendingKeyLimit() {
        assertThatThrownBy(() -> new AsyncApiKeyUsageRecorder(new CountingRepository(), 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxPendingKeys");
    }

    @Test
    void ignoresUsageUntilStarted() {
        CountingRepository repository = new CountingRepository();
        AsyncApiKeyUsageRecorder recorder = new AsyncApiKeyUsageRecorder(repository, 10, Duration.ofSeconds(60), Duration.ofSeconds(1));

        recorder.recordUsage("key-1");
        recorder.flushPending();

        assertThat(repository.incrementCount()).isZero();
        assertThat(recorder.pendingKeyCount()).isZero();
    }

    @Test
    void preservesMultipleCountsForSameKey() {
        CountingRepository repository = new CountingRepository();
        AsyncApiKeyUsageRecorder recorder = new AsyncApiKeyUsageRecorder(repository, 10, Duration.ofSeconds(60), Duration.ofSeconds(1));

        recorder.start();
        try {
            recorder.recordUsage("key-1");
            recorder.recordUsage("key-1");
            recorder.recordUsage("key-1");

            assertThat(recorder.pendingKeyCount()).isEqualTo(1);
            recorder.flushPending();

            assertThat(repository.incrementCount()).isEqualTo(3);
            assertThat(repository.batchCallCount()).isEqualTo(1);
            assertThat(recorder.pendingKeyCount()).isZero();
        } finally {
            recorder.stop();
        }
    }

    @Test
    void boundsDistinctPendingKeys() {
        CountingRepository repository = new CountingRepository();
        AsyncApiKeyUsageRecorder recorder = new AsyncApiKeyUsageRecorder(repository, 1, Duration.ofSeconds(60), Duration.ofSeconds(1));

        recorder.start();
        try {
            recorder.recordUsage("key-1");
            recorder.recordUsage("key-2");
            recorder.flushPending();

            assertThat(repository.incrementCount()).isEqualTo(1);
            assertThat(repository.batchCallCount()).isEqualTo(1);
            assertThat(repository.lastIncrementedId()).isEqualTo("key-1");
        } finally {
            recorder.stop();
        }
    }

    @Test
    void stopFlushesPendingUsage() {
        CountingRepository repository = new CountingRepository();
        AsyncApiKeyUsageRecorder recorder = new AsyncApiKeyUsageRecorder(repository, 10, Duration.ofSeconds(60), Duration.ofSeconds(1));

        recorder.start();
        recorder.recordUsage("key-1");
        recorder.recordUsage("key-1");
        recorder.stop();

        assertThat(repository.incrementCount()).isEqualTo(2);
        assertThat(repository.batchCallCount()).isEqualTo(1);
        assertThat(recorder.isRunning()).isFalse();
        assertThat(recorder.pendingKeyCount()).isZero();
    }

    private static class CountingRepository implements ApiKeyRepository {
        private final AtomicInteger increments = new AtomicInteger();
        private final AtomicInteger batchCalls = new AtomicInteger();
        private String lastIncrementedId;

        @Override
        public ApiKey save(ApiKey apiKey) { return apiKey; }

        @Override
        public Optional<ApiKey> findById(String id) { return Optional.empty(); }

        @Override
        public Optional<ApiKey> findByKeyHash(String keyHash) { return Optional.empty(); }

        @Override
        public Optional<ApiKey> findByKeyPrefix(String keyPrefix) { return Optional.empty(); }

        @Override
        public List<ApiKey> findAll() { return List.of(); }

        @Override
        public List<ApiKey> findByStatus(ApiKey.Status status) { return List.of(); }

        @Override
        public List<ApiKey> findByTier(ApiKey.Tier tier) { return List.of(); }

        @Override
        public boolean existsByKeyHash(String keyHash) { return false; }

        @Override
        public boolean deleteById(String id) { return false; }

        @Override
        public long count() { return 0; }

        @Override
        public long countByStatus(ApiKey.Status status) { return 0; }

        @Override
        public ApiKey incrementUsage(String id) {
            increments.incrementAndGet();
            lastIncrementedId = id;
            return null;
        }

        @Override
        public void incrementUsageBatch(Map<String, Long> usageCounts) {
            batchCalls.incrementAndGet();
            usageCounts.forEach((id, count) -> {
                increments.addAndGet(Math.toIntExact(count));
                lastIncrementedId = id;
            });
        }

        int incrementCount() { return increments.get(); }

        int batchCallCount() { return batchCalls.get(); }

        String lastIncrementedId() { return lastIncrementedId; }
    }
}

package io.junction.gateway.starter.observability;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.starter.JunctionProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JunctionEndpointTest {

    @Test
    void snapshotUsesCachedProviderHealthSnapshotsOnly() {
        var router = mock(Router.class);
        var modelCacheService = mock(ModelCacheService.class);
        var apiKeyRepository = mock(ApiKeyRepository.class);
        var checkedAt = Instant.parse("2026-05-06T00:00:00Z");
        when(router.getProviderHealthSnapshots()).thenReturn(List.of(
            new Router.ProviderHealthSnapshot("ollama", false, checkedAt, true, true)
        ));
        when(modelCacheService.snapshot()).thenReturn(List.of());
        when(apiKeyRepository.count()).thenReturn(4L);
        when(apiKeyRepository.countByStatus(ApiKey.Status.ACTIVE)).thenReturn(1L);
        when(apiKeyRepository.countByStatus(ApiKey.Status.SUSPENDED)).thenReturn(1L);
        when(apiKeyRepository.countByStatus(ApiKey.Status.REVOKED)).thenReturn(1L);
        when(apiKeyRepository.countByStatus(ApiKey.Status.EXPIRED)).thenReturn(1L);

        var snapshot = new JunctionEndpoint(
            router,
            modelCacheService,
            apiKeyRepository,
            new JunctionProperties()
        ).snapshot();

        assertThat(snapshot.providers()).hasSize(1);
        assertThat(snapshot.providers().getFirst().providerId()).isEqualTo("ollama");
        assertThat(snapshot.providers().getFirst().healthy()).isFalse();
        assertThat(snapshot.providers().getFirst().checkedAt()).isEqualTo(checkedAt);
        assertThat(snapshot.apiKeys().total()).isEqualTo(4L);
        verify(router).getProviderHealthSnapshots();
        verifyNoMoreInteractions(router);
    }
}

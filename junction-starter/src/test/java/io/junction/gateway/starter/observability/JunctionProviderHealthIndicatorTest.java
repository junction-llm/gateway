package io.junction.gateway.starter.observability;

import io.junction.gateway.core.router.Router;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JunctionProviderHealthIndicatorTest {

    @Test
    void healthUsesCachedProviderSnapshotsOnly() {
        var router = mock(Router.class);
        var checkedAt = Instant.parse("2026-05-06T00:00:00Z");
        when(router.getProviderHealthSnapshots()).thenReturn(List.of(
            new Router.ProviderHealthSnapshot("ollama", true, checkedAt, true, true),
            new Router.ProviderHealthSnapshot("gemini", null, null, false, false)
        ));

        var health = new JunctionProviderHealthIndicator(router).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("configuredProviders", 2);
        assertThat(health.getDetails().get("providers")).asString().contains("ollama", "gemini", "unknown");
        verify(router).getProviderHealthSnapshots();
        verifyNoMoreInteractions(router);
    }
}

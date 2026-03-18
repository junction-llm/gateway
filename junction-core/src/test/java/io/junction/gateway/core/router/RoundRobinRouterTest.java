package io.junction.gateway.core.router;

import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.provider.GeminiProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundRobinRouterTest {

    @Test
    void routeEmbeddingsRejectsProvidersWithoutEmbeddingsSupport() {
        var router = new RoundRobinRouter(List.of(new GeminiProvider("test-api-key", "gemini-1.5-flash")));
        var request = new EmbeddingRequest("embeddinggemma", List.of("hello"), null, null, null);

        assertThrows(NoProviderAvailableException.class, () -> router.route(request));
    }
}

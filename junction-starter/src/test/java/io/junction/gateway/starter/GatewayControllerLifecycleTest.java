package io.junction.gateway.starter;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.ApiKeyValidator;
import io.junction.gateway.core.security.IpRateLimiter;
import io.junction.gateway.core.tracing.GatewayTracing;
import io.junction.gateway.starter.clientcompat.ClientCompatibilityService;
import io.junction.gateway.starter.observability.JunctionObservabilityService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GatewayControllerLifecycleTest {

    @Test
    void shutdownStopsOwnedStreamIdleTimeoutExecutor() throws Exception {
        var controller = new GatewayController(
            mock(Router.class),
            tools.jackson.databind.json.JsonMapper.builder().build(),
            mock(ClientCompatibilityService.class),
            mock(ApiKeyValidator.class),
            mock(IpRateLimiter.class),
            new JunctionProperties(),
            mock(ModelCacheService.class),
            mock(JunctionObservabilityService.class),
            mock(GatewayTracing.class)
        );

        var executor = streamIdleTimeoutExecutor(controller);
        assertThat(executor.isShutdown()).isFalse();

        controller.shutdownStreamIdleTimeoutExecutor();

        assertThat(executor.isShutdown()).isTrue();
    }

    private static ScheduledExecutorService streamIdleTimeoutExecutor(GatewayController controller) throws Exception {
        Field field = GatewayController.class.getDeclaredField("streamIdleTimeoutExecutor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(controller);
    }
}

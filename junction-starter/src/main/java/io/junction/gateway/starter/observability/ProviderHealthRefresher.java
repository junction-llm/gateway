package io.junction.gateway.starter.observability;

import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.starter.JunctionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refreshes provider health outside user request, actuator, and administrative
 * read paths so those paths consume cached health snapshots only.
 */
public class ProviderHealthRefresher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ProviderHealthRefresher.class);

    private final Router router;
    private final JunctionProperties.Health properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public ProviderHealthRefresher(Router router, JunctionProperties.Health properties) {
        this.router = router;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!properties.isProviderRefreshEnabled() || !running.compareAndSet(false, true)) {
            return;
        }

        var intervalMillis = Math.max(1_000L, properties.getProviderRefreshIntervalMillis());
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
            .name("junction-provider-health-refresh-", 0)
            .factory());

        if (properties.isProviderRefreshOnStartup()) {
            refreshOnce("startup");
        }

        task = executor.scheduleWithFixedDelay(
            () -> refreshOnce("scheduled"),
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS
        );
    }

    void refreshOnce(String operation) {
        if (router instanceof RoundRobinRouter roundRobinRouter) {
            for (var provider : roundRobinRouter.getProviders()) {
                roundRobinRouter.refreshHealth(provider, operation);
            }
        } else {
            log.debug("Provider health refresh skipped because router {} does not expose refresh support", router.getClass().getName());
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}

package io.junction.gateway.starter;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.provider.GeminiProvider;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.*;
import io.junction.gateway.core.telemetry.GatewayTelemetry;
import io.junction.gateway.core.tracing.GatewayTracing;
import io.junction.gateway.starter.clientcompat.*;
import io.junction.gateway.starter.config.JacksonConfig;
import io.junction.gateway.starter.observability.*;
import io.junction.gateway.starter.security.ApiKeyInitializer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;

/**
 * Spring Boot auto-configuration for Junction Gateway.
 * 
 * <p>Wires up all components including:
 * <ul>
 *   <li>LLM providers (Ollama, Gemini)</li>
 *   <li>Request router</li>
 *   <li>API key security (repository, rate limiter, validator)</li>
 *   <li>Client compatibility services</li>
 *   <li>Gateway controller</li>
 * </ul>
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@AutoConfiguration(before = OpenTelemetryTracingAutoConfiguration.class)
@EnableConfigurationProperties({JunctionProperties.class, ClientAdapterProperties.class})
@Import({JacksonConfig.class, JunctionActuatorSecurityConfiguration.class})
public class JunctionAutoConfiguration {
    
    
    @Bean
    @ConditionalOnMissingBean(GatewayTelemetry.class)
    public GatewayTelemetry gatewayTelemetry(MeterRegistry meterRegistry) {
        return new MicrometerGatewayTelemetry(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(Slf4JEventListener.class)
    public Slf4JEventListener junctionOtelSlf4JEventListener() {
        return new Slf4JEventListener("otelTraceId", "otelSpanId");
    }

    @Bean
    @ConditionalOnMissingBean(GatewayTracing.class)
    public GatewayTracing gatewayTracing(Tracer tracer, Propagator propagator) {
        return new MicrometerGatewayTracing(tracer, propagator);
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "junction.providers.ollama", name = "enabled", havingValue = "true")
    public OllamaProvider ollamaProvider(JunctionProperties props,
                                         GatewayTelemetry telemetry,
                                         GatewayTracing tracing) {
        return new OllamaProvider(
            props.getOllama().getBaseUrl(),
            props.getOllama().getDefaultModel(),
            telemetry,
            tracing
        );
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "junction.providers.gemini", name = "enabled", havingValue = "true")
    public GeminiProvider geminiProvider(JunctionProperties props,
                                         GatewayTelemetry telemetry,
                                         GatewayTracing tracing) {
        return new GeminiProvider(
            props.getGemini().getApiKey(),
            props.getGemini().getModel(),
            telemetry,
            tracing
        );
    }
    
    @Bean
    @ConditionalOnMissingBean(Router.class)
    public Router router(JunctionProperties props, 
                        org.springframework.context.ApplicationContext ctx,
                        GatewayTelemetry telemetry,
                        GatewayTracing tracing) {
        var providers = new ArrayList<io.junction.gateway.core.provider.LlmProvider>();
        
        if (props.getOllama().isEnabled()) {
            providers.add(ctx.getBean(OllamaProvider.class));
        }
        if (props.getGemini().isEnabled()) {
            providers.add(ctx.getBean(GeminiProvider.class));
        }
        
        return new RoundRobinRouter(providers, telemetry, tracing);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCacheService.class)
    public ModelCacheService modelCacheService(GatewayTelemetry telemetry, GatewayTracing tracing) {
        return new ModelCacheService(telemetry, tracing);
    }

    @Bean
    public JunctionObservabilityService junctionObservabilityService(MeterRegistry meterRegistry) {
        return new JunctionObservabilityService(meterRegistry);
    }

    @Bean
    public Gauge junctionModelCacheEntriesGauge(MeterRegistry meterRegistry, ModelCacheService modelCacheService) {
        return Gauge.builder("junction.model.cache.entries", modelCacheService, ModelCacheService::getCacheSize)
            .description("Number of provider model caches currently populated")
            .register(meterRegistry);
    }

    @Bean
    public HealthIndicator junctionProviderHealthIndicator(Router router) {
        return new JunctionProviderHealthIndicator(router);
    }

    @Bean
    public InfoContributor junctionInfoContributor(Router router,
                                                   JunctionProperties props,
                                                   ModelCacheService modelCacheService) {
        return new JunctionInfoContributor(router, props, modelCacheService);
    }

    @Bean
    public JunctionEndpoint junctionEndpoint(Router router,
                                             ModelCacheService modelCacheService,
                                             ApiKeyRepository apiKeyRepository,
                                             JunctionProperties properties) {
        return new JunctionEndpoint(router, modelCacheService, apiKeyRepository, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "junction.observability.admin", name = "cache-write-enabled", havingValue = "true")
    public JunctionCacheEndpoint junctionCacheEndpoint(ModelCacheService modelCacheService) {
        return new JunctionCacheEndpoint(modelCacheService);
    }
    
    
    
    @Bean
    @ConditionalOnMissingBean(ApiKeyRepository.class)
    public ApiKeyRepository apiKeyRepository() {
        
        return new InMemoryApiKeyRepository();
    }
    
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter rateLimiter() {
        
        return new InMemoryRateLimiter();
    }
    
    @Bean
    @ConditionalOnMissingBean(IpRateLimiter.class)
    public IpRateLimiter ipRateLimiter(JunctionProperties props) {
        var config = props.getSecurity().getIpRateLimit();
        return new IpRateLimiter(
            config.getRequestsPerMinute(),
            config.getRequestsPerHour(),
            config.isEnabled()
        );
    }
    
    @Bean
    public ApiKeyValidator apiKeyValidator(ApiKeyRepository repository, 
                                           RateLimiter rateLimiter,
                                           JunctionProperties props) {
        return new ApiKeyValidator(
            repository,
            rateLimiter,
            props.getApiKeyConfig().isRequired()
        );
    }
    
    @Bean
    public ApiKeyInitializer apiKeyInitializer(ApiKeyRepository repository,
                                                ApiKeyValidator validator,
                                                JunctionProperties props) {
        return new ApiKeyInitializer(repository, validator, props);
    }
    
    
    
    @Bean
    public ClientAdapterLoader clientAdapterLoader(
            org.springframework.core.io.ResourceLoader resourceLoader,
            ClientAdapterProperties properties) {
        return new ClientAdapterLoader(resourceLoader, properties);
    }
    
    @Bean
    public ClientDetectionService clientDetectionService(ClientAdapterLoader adapterLoader) {
        return new ClientDetectionService(adapterLoader);
    }
    
    @Bean
    public ClientCompatibilityService clientCompatibilityService(ClientDetectionService detectionService) {
        return new ClientCompatibilityService(detectionService);
    }
    
    
    
    @Bean
    public GatewayController gatewayController(Router router, 
                                               JsonMapper jsonMapper,
                                               ClientCompatibilityService clientCompatService,
                                               ApiKeyValidator apiKeyValidator,
                                               IpRateLimiter ipRateLimiter,
                                               JunctionProperties junctionProperties,
                                               ModelCacheService modelCacheService,
                                               JunctionObservabilityService observabilityService,
                                               GatewayTracing tracing) {
        return new GatewayController(router, jsonMapper, clientCompatService, apiKeyValidator, ipRateLimiter, junctionProperties, modelCacheService, observabilityService, tracing);
    }
}

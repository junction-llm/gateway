package io.junction.gateway.starter;

import io.junction.gateway.core.provider.GeminiProvider;
import io.junction.gateway.core.provider.OllamaProvider;
import io.junction.gateway.core.router.RoundRobinRouter;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.*;
import io.junction.gateway.starter.clientcompat.*;
import io.junction.gateway.starter.config.JacksonConfig;
import io.junction.gateway.starter.security.ApiKeyInitializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@AutoConfiguration
@EnableConfigurationProperties({JunctionProperties.class, ClientAdapterProperties.class})
@Import(JacksonConfig.class)
public class JunctionAutoConfiguration {
    
    
    
    @Bean
    @ConditionalOnProperty(prefix = "junction.providers.ollama", name = "enabled", havingValue = "true")
    public OllamaProvider ollamaProvider(JunctionProperties props) {
        return new OllamaProvider(
            props.getOllama().getBaseUrl(),
            props.getOllama().getDefaultModel()
        );
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "junction.providers.gemini", name = "enabled", havingValue = "true")
    public GeminiProvider geminiProvider(JunctionProperties props) {
        return new GeminiProvider(
            props.getGemini().getApiKey(),
            props.getGemini().getModel()
        );
    }
    
    @Bean
    @ConditionalOnMissingBean(Router.class)
    public Router router(JunctionProperties props, 
                        org.springframework.context.ApplicationContext ctx) {
        var providers = new ArrayList<io.junction.gateway.core.provider.LlmProvider>();
        
        if (props.getOllama().isEnabled()) {
            providers.add(ctx.getBean(OllamaProvider.class));
        }
        if (props.getGemini().isEnabled()) {
            providers.add(ctx.getBean(GeminiProvider.class));
        }
        
        return new RoundRobinRouter(providers);
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
                                               JunctionProperties junctionProperties) {
        return new GatewayController(router, jsonMapper, clientCompatService, apiKeyValidator, ipRateLimiter, junctionProperties);
    }
}

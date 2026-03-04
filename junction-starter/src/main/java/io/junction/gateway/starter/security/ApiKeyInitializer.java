package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.ApiKeyValidator;
import io.junction.gateway.starter.JunctionProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Initializes API keys on application startup.
 * 
 * <p>Loads preconfigured keys from application.yml and registers them
 * in the API key repository.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Component
public class ApiKeyInitializer {
    
    private static final Logger log = LoggerFactory.getLogger(ApiKeyInitializer.class);
    
    private final ApiKeyRepository repository;
    private final ApiKeyValidator validator;
    private final JunctionProperties properties;
    
    public ApiKeyInitializer(ApiKeyRepository repository, 
                             ApiKeyValidator validator,
                             JunctionProperties properties) {
        this.repository = repository;
        this.validator = validator;
        this.properties = properties;
    }
    
    @PostConstruct
    public void initialize() {
        List<JunctionProperties.PreconfiguredKey> preconfigured = 
            properties.getApiKeyConfig().getPreconfigured();
        
        if (preconfigured == null || preconfigured.isEmpty()) {
            log.info("No preconfigured API keys found");
            
            if (properties.getApiKeyConfig().isRequired()) {
                log.warn("API key authentication is required but no keys are configured!");
                log.warn("Use 'junction.security.api-key.preconfigured' to add keys, or use the API to create keys.");
            }
            return;
        }
        
        log.info("Initializing {} preconfigured API keys", preconfigured.size());
        
        for (JunctionProperties.PreconfiguredKey config : preconfigured) {
            if (config.getKey() == null || config.getKey().isBlank()) {
                log.warn("Skipping preconfigured key with empty value");
                continue;
            }
            
            String keyHash = validator.hashKey(config.getKey());
            if (repository.existsByKeyHash(keyHash)) {
                log.debug("API key '{}' already exists, skipping", config.getName());
                continue;
            }
            
            ApiKey.Tier tier;
            try {
                tier = ApiKey.Tier.valueOf(config.getTier().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tier '{}', defaulting to FREE", config.getTier());
                tier = ApiKey.Tier.FREE;
            }
            
            String prefix = validator.extractPrefix(config.getKey());
            ApiKey apiKey = ApiKey.createNew(keyHash, prefix, config.getName(), tier);
            
            repository.save(apiKey);
            log.info("Registered preconfigured API key: {} (tier: {}, prefix: {})", 
                config.getName(), tier, prefix);
        }

        if (repository.count() == 0 && properties.getApiKeyConfig().isRequired()) {
            log.warn("API key authentication is required but no usable keys were loaded.");
            log.warn("Set JUNCTION_API_KEY_1 (or junction.security.api-key.preconfigured) before exposing the gateway.");
        }
        
        log.info("API key initialization complete. Total keys: {}", repository.count());
    }
}

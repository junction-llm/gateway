package io.junction.gateway.starter.config;

import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.json.JsonMapper;
import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.model.ChatCompletionRequestDeserializer;
import io.junction.gateway.core.model.EmbeddingRequest;
import io.junction.gateway.core.model.EmbeddingRequestDeserializer;
import io.junction.gateway.core.model.MessageDeserializer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 3 configuration for Spring Boot 4 with custom deserializers
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Configuration
public class JacksonConfig {
    
    /**
     * SimpleModule bean is auto-discovered by Spring Boot and registered with ObjectMapper
     * 
     * @return the custom Jackson module with deserializers for chat completion requests
     */
    @Bean(name = "chatCompletionModule")
    public SimpleModule chatCompletionModule() {
        SimpleModule module = new SimpleModule("ChatCompletionModule");
        module.addDeserializer(ChatCompletionRequest.class, new ChatCompletionRequestDeserializer());
        module.addDeserializer(ChatCompletionRequest.Message.class, new MessageDeserializer());
        module.addDeserializer(EmbeddingRequest.class, new EmbeddingRequestDeserializer());
        return module;
    }
    
    /**
     * Configure JsonMapper to skip null values in serialization
     * 
     * @return a JsonMapperBuilderCustomizer that sets the default property inclusion to NON_NULL
     */
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder
            .changeDefaultPropertyInclusion(include -> 
                include.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            );
    }
}

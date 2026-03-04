package io.junction.gateway.core.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import java.util.ArrayList;
import java.util.List;


public class MessageDeserializer extends ValueDeserializer<ChatCompletionRequest.Message> {
    
    @Override
    public ChatCompletionRequest.Message deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonNode node = ctxt.readTree(p);
        
        String role = node.path("role").asText();
        var contentNode = node.path("content");
        
        List<ChatCompletionRequest.ContentPart> content = deserializeContent(contentNode);
        return new ChatCompletionRequest.Message(role, content);
    }
    
    private List<ChatCompletionRequest.ContentPart> deserializeContent(JsonNode node) {
        if (node.isTextual()) {
            return List.of(ChatCompletionRequest.ContentPart.text(node.asText()));
        }
        
        if (node.isArray()) {
            List<ChatCompletionRequest.ContentPart> parts = new ArrayList<>();
            for (var element : node) {
                String type = element.path("type").asText("text");
                
                if ("text".equals(type)) {
                    String text = element.path("text").asText();
                    parts.add(ChatCompletionRequest.ContentPart.text(text));
                } else if ("image_url".equals(type)) {
                    var imageUrlNode = element.path("image_url");
                    String url = imageUrlNode.isTextual() 
                        ? imageUrlNode.asText() 
                        : imageUrlNode.path("url").asText();
                    parts.add(ChatCompletionRequest.ContentPart.imageUrl(url));
                }
            }
            return List.copyOf(parts);
        }
        
        return List.of();
    }
}

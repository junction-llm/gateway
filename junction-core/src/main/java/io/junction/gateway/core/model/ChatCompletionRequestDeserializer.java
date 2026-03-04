package io.junction.gateway.core.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;


public class ChatCompletionRequestDeserializer extends ValueDeserializer<ChatCompletionRequest> {
    
    @Override
    public ChatCompletionRequest deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonNode node = ctxt.readTree(p);
        
        String model = node.path("model").asText();
        boolean stream = node.path("stream").asBoolean(false);
        
        JsonNode tempNode = node.path("temperature");
        Double temperature = (tempNode instanceof MissingNode) ? null : tempNode.asDouble();
        
        JsonNode maxTokensNode = node.path("max_tokens");
        Integer maxTokens = (maxTokensNode instanceof MissingNode) ? null : maxTokensNode.asInt();
        
        List<ChatCompletionRequest.Message> messages = new ArrayList<>();
        var messagesNode = node.path("messages");
        if (messagesNode.isArray()) {
            for (var msgNode : messagesNode) {
                String role = msgNode.path("role").asText();
                var contentNode = msgNode.path("content");
                
                List<ChatCompletionRequest.ContentPart> content = deserializeContent(contentNode);
                messages.add(new ChatCompletionRequest.Message(role, content));
            }
        }
        
        return new ChatCompletionRequest(model, messages, stream, temperature, maxTokens);
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

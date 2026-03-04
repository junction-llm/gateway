package io.junction.gateway.starter.clientcompat;

import io.junction.gateway.core.model.ChatCompletionChunk;
import io.junction.gateway.core.model.ChatCompletionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main service for client compatibility.
 * Detects clients and applies appropriate patches to requests and responses.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Service
public class ClientCompatibilityService {
    private static final Logger log = LoggerFactory.getLogger(ClientCompatibilityService.class);
    
    private final ClientDetectionService detectionService;
    
    @Autowired
    public ClientCompatibilityService(ClientDetectionService detectionService) {
        this.detectionService = detectionService;
    }
    
    /**
     * Detect the client adapter for the given request
     * 
     * @param request the HTTP servlet request
     * @return the matching adapter config, or null if no match
     */
    public ClientAdapterConfig detectClient(HttpServletRequest request) {
        return detectionService.detectAdapter(request);
    }
    
    /**
     * Apply request patches based on the detected client adapter
     * 
     * @param request the chat completion request
     * @param adapterConfig the detected adapter configuration
     * @return the patched request
     */
    public ChatCompletionRequest applyRequestPatches(ChatCompletionRequest request, ClientAdapterConfig adapterConfig) {
        if (adapterConfig == null || adapterConfig.getPatches() == null) {
            return request;
        }
        
        log.info("Applying request patches for adapter: {}, working on it...", adapterConfig.getId());
        
        ChatCompletionRequest patchedRequest = request;
        
        ClientAdapterConfig.SystemPromptInjectionConfig systemPromptConfig = 
            adapterConfig.getPatches().getSystemPromptInjection();
        if (systemPromptConfig != null && systemPromptConfig.isEnabled()) {
            patchedRequest = applySystemPromptInjection(patchedRequest, systemPromptConfig);
        }
        
        List<ClientAdapterConfig.RequestTransformConfig> requestTransforms = 
            adapterConfig.getPatches().getRequestTransforms();
        if (requestTransforms != null) {
            for (ClientAdapterConfig.RequestTransformConfig transform : requestTransforms) {
                if (transform.isEnabled()) {
                    patchedRequest = applyRequestTransform(patchedRequest, transform);
                }
            }
        }
        
        return patchedRequest;
    }
    
    /**
     * Apply response patches based on the detected client adapter
     * 
     * @param chunk the chat completion chunk
     * @param adapterConfig the detected adapter configuration
     * @return the patched chunk
     */
    public ChatCompletionChunk applyResponsePatches(ChatCompletionChunk chunk, ClientAdapterConfig adapterConfig) {
        if (adapterConfig == null || adapterConfig.getPatches() == null) {
            return chunk;
        }
        
        ChatCompletionChunk patchedChunk = chunk;
        
        List<ClientAdapterConfig.ResponseTransformConfig> responseTransforms = 
            adapterConfig.getPatches().getResponseTransforms();
        if (responseTransforms != null) {
            boolean patchesApplied = false;
            for (ClientAdapterConfig.ResponseTransformConfig transform : responseTransforms) {
                if (transform.isEnabled()) {
                    if (!patchesApplied) {
                        patchesApplied = true;
                    }
                    patchedChunk = applyResponseTransform(patchedChunk, transform);
                }
            }
        }
        
        return patchedChunk;
    }
    
    private ChatCompletionRequest applySystemPromptInjection(
            ChatCompletionRequest request, 
            ClientAdapterConfig.SystemPromptInjectionConfig config) {
        
        String content = config.getContent();
        if (content == null || content.isEmpty()) {
            return request;
        }
        
        String position = config.getPosition();
        if (position == null) {
            position = "prepend";
        }
        
        List<ChatCompletionRequest.Message> messages = new ArrayList<>(request.messages());
        
        int systemMessageIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).role())) {
                systemMessageIndex = i;
                break;
            }
        }
        
        switch (position.toLowerCase()) {
            case "prepend" -> {
                if (systemMessageIndex >= 0) {
                    ChatCompletionRequest.Message existingSystem = messages.get(systemMessageIndex);
                    String existingContent = existingSystem.getTextContent();
                    String newContent = content + "\n\n" + existingContent;
                    messages.set(systemMessageIndex, new ChatCompletionRequest.Message("system", newContent));
                    log.debug("Prepended system prompt injection to existing system message");
                } else {
                    messages.add(0, new ChatCompletionRequest.Message("system", content));
                    log.debug("Added system prompt injection as new first message");
                }
            }
            case "append" -> {
                if (systemMessageIndex >= 0) {
                    ChatCompletionRequest.Message existingSystem = messages.get(systemMessageIndex);
                    String existingContent = existingSystem.getTextContent();
                    String newContent = existingContent + "\n\n" + content;
                    messages.set(systemMessageIndex, new ChatCompletionRequest.Message("system", newContent));
                    log.debug("Appended system prompt injection to existing system message");
                } else {
                    messages.add(0, new ChatCompletionRequest.Message("system", content));
                    log.debug("Added system prompt injection as new first message");
                }
            }
            case "replace" -> {
                if (systemMessageIndex >= 0) {
                    messages.set(systemMessageIndex, new ChatCompletionRequest.Message("system", content));
                    log.debug("Replaced existing system message with prompt injection");
                } else {
                    messages.add(0, new ChatCompletionRequest.Message("system", content));
                    log.debug("Added system prompt injection as new first message (replace mode, no existing system message)");
                }
            }
            default -> {
                log.warn("Unknown system prompt position: {}, defaulting to prepend", position);
                if (systemMessageIndex >= 0) {
                    ChatCompletionRequest.Message existingSystem = messages.get(systemMessageIndex);
                    String existingContent = existingSystem.getTextContent();
                    String newContent = content + "\n\n" + existingContent;
                    messages.set(systemMessageIndex, new ChatCompletionRequest.Message("system", newContent));
                } else {
                    messages.add(0, new ChatCompletionRequest.Message("system", content));
                }
            }
        }
        
        return new ChatCompletionRequest(
            request.model(),
            messages,
            request.stream(),
            request.temperature(),
            request.maxTokens()
        );
    }
    
    /**
     * Apply a request transformation
     * @param request the original request
     * @param transform the transformation configuration
     * @return the transformed request
     */
    private ChatCompletionRequest applyRequestTransform(
            ChatCompletionRequest request,
            ClientAdapterConfig.RequestTransformConfig transform) {
        
        String type = transform.getType();
        Map<String, Object> props = transform.getProperties();
        
        switch (type) {
            case "ensure-alternating-roles" -> {
                log.debug("Applying ensure-alternating-roles transform");
            }
            case "add-header-to-provider" -> {
                log.debug("Applying add-header-to-provider transform");
            }
            default -> {
                log.warn("Unknown request transform type: {}", type);
            }
        }
        
        return request;
    }
    
    private ChatCompletionChunk applyResponseTransform(
            ChatCompletionChunk chunk,
            ClientAdapterConfig.ResponseTransformConfig transform) {
        
        String type = transform.getType();
        Map<String, Object> props = transform.getProperties();
        
        switch (type) {
            case "fix-missing-xml-tags" -> {
                return fixMissingXmlTags(chunk, props);
            }
            case "validate-xml-structure" -> {
                log.debug("Applying validate-xml-structure transform");
            }
            case "convert-tool-args-to-object" -> {
                return convertToolArgsToObject(chunk, props);
            }
            default -> {
                log.warn("Unknown response transform type: {}", type);
            }
        }
        
        return chunk;
    }
    
    private ChatCompletionChunk fixMissingXmlTags(ChatCompletionChunk chunk, Map<String, Object> props) {
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return chunk;
        }
        
        var choice = chunk.choices().get(0);
        if (choice.delta() == null || choice.delta().content() == null) {
            return chunk;
        }
        
        String content = choice.delta().content();
        String originalContent = content;
        
        if (content.contains("<read_file>") && !content.contains("<path>")) {
            content = content.replaceAll(
                "(<read_file>)([^<]*)(</read_file>)",
                "$1<path>$2</path>$3"
            );
            log.debug("Fixed missing <path> tag in <read_file>");
        }
        
        if (content.contains("<execute_command>") && !content.contains("<command>")) {
            content = content.replaceAll(
                "(<execute_command>)([^<]*)(</execute_command>)",
                "$1<command>$2</command>$3"
            );
            log.debug("Fixed missing <command> tag in <execute_command>");
        }
        
        if (content.contains("<plan_mode_respond>") && !content.contains("<response>")) {
            content = content.replaceAll(
                "(<plan_mode_respond>)([^<]*)(</plan_mode_respond>)",
                "$1<response>$2</response>$3"
            );
            log.debug("Fixed missing <response> tag in <plan_mode_respond>");
        }
        
        if (!content.equals(originalContent)) {
            log.debug("Applied XML tag fixes to response content");
            
            var newDelta = new ChatCompletionChunk.ChunkChoice.Delta(
                content,
                choice.delta().toolCalls()
            );
            
            var newChoice = new ChatCompletionChunk.ChunkChoice(
                choice.index(),
                newDelta,
                choice.finishReason()
            );
            
            return new ChatCompletionChunk(
                chunk.id(),
                chunk.created(),
                chunk.model(),
                List.of(newChoice)
            );
        }
        
        return chunk;
    }
    
    /**
     * Convert tool call arguments from JSON string to JSON object format.
     * This is needed for clients like Roo Code that expect arguments as objects, not strings.
     * 
     * @param chunk the original response chunk
     * @param props transformation properties (not used in this transform)
     * @return the transformed chunk with tool call arguments converted to objects
     */
    private ChatCompletionChunk convertToolArgsToObject(ChatCompletionChunk chunk, Map<String, Object> props) {
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return chunk;
        }
        
        var choice = chunk.choices().get(0);
        if (choice.delta() == null || choice.delta().toolCalls() == null || choice.delta().toolCalls().isEmpty()) {
            return chunk;
        }
        
        boolean modified = false;
        List<ChatCompletionChunk.ToolCall> newToolCalls = new ArrayList<>();
        
        for (var toolCall : choice.delta().toolCalls()) {
            if (toolCall.function() != null && toolCall.function().arguments() != null) {
                String args = toolCall.function().arguments();
                
                if (args.startsWith("\"") && args.endsWith("\"")) {
                    try {
                        String unescaped = args.substring(1, args.length() - 1)
                            .replace("\\\\", "\\")
                            .replace("\\\"", "\"");
                        
                        var mapper = new tools.jackson.databind.ObjectMapper();
                        JsonNode jsonNode = mapper.readTree(unescaped);
                        
                        var newFunction = new ChatCompletionChunk.ToolCall.Function(
                            toolCall.function().name(),
                            unescaped
                        );
                        
                        newToolCalls.add(new ChatCompletionChunk.ToolCall(
                            toolCall.index(),
                            toolCall.id(),
                            toolCall.type(),
                            newFunction
                        ));
                        
                        modified = true;
                        log.debug("Converted tool call arguments from escaped string to JSON format");
                        
                    } catch (Exception e) {
                        log.warn("Failed to parse tool call arguments as JSON: {}", args, e);
                        newToolCalls.add(toolCall);
                    }
                } else {
                    newToolCalls.add(toolCall);
                }
            } else {
                newToolCalls.add(toolCall);
            }
        }
        
        if (modified) {
            var newDelta = new ChatCompletionChunk.ChunkChoice.Delta(
                choice.delta().content(),
                newToolCalls
            );
            
            var newChoice = new ChatCompletionChunk.ChunkChoice(
                choice.index(),
                newDelta,
                choice.finishReason()
            );
            
            return new ChatCompletionChunk(
                chunk.id(),
                chunk.created(),
                chunk.model(),
                List.of(newChoice)
            );
        }
        
        return chunk;
    }
}

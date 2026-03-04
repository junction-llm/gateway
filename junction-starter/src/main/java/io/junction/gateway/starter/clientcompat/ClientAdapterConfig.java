package io.junction.gateway.starter.clientcompat;

import java.util.List;
import java.util.Map;

/**
 * Configuration class for client adapter YAML files.
 * Maps to the structure of client-adapter YAML configurations.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class ClientAdapterConfig {
    private String id;
    private String name;
    private String description;
    private DetectionConfig detection;
    private PatchConfig patches;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public DetectionConfig getDetection() { return detection; }
    public void setDetection(DetectionConfig detection) { this.detection = detection; }
    
    public PatchConfig getPatches() { return patches; }
    public void setPatches(PatchConfig patches) { this.patches = patches; }

    public static class DetectionConfig {
        private Map<String, String> headers;
        private Map<String, String> headerContains;
        private String versionRange;

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        
        public Map<String, String> getHeaderContains() { return headerContains; }
        public void setHeaderContains(Map<String, String> headerContains) { this.headerContains = headerContains; }
        
        public String getVersionRange() { return versionRange; }
        public void setVersionRange(String versionRange) { this.versionRange = versionRange; }
    }

    public static class PatchConfig {
        private SystemPromptInjectionConfig systemPromptInjection;
        private List<RequestTransformConfig> requestTransforms;
        private List<ResponseTransformConfig> responseTransforms;

        public SystemPromptInjectionConfig getSystemPromptInjection() { return systemPromptInjection; }
        public void setSystemPromptInjection(SystemPromptInjectionConfig systemPromptInjection) { 
            this.systemPromptInjection = systemPromptInjection; 
        }
        
        public List<RequestTransformConfig> getRequestTransforms() { return requestTransforms; }
        public void setRequestTransforms(List<RequestTransformConfig> requestTransforms) { 
            this.requestTransforms = requestTransforms; 
        }
        
        public List<ResponseTransformConfig> getResponseTransforms() { return responseTransforms; }
        public void setResponseTransforms(List<ResponseTransformConfig> responseTransforms) { 
            this.responseTransforms = responseTransforms; 
        }
    }

    public static class SystemPromptInjectionConfig {
        private boolean enabled;
        private String position;
        private String content;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getPosition() { return position; }
        public void setPosition(String position) { this.position = position; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class RequestTransformConfig {
        private String type;
        private boolean enabled;
        private Map<String, Object> properties;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }

    public static class ResponseTransformConfig {
        private String type;
        private boolean enabled;
        private Map<String, Object> properties;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }
}
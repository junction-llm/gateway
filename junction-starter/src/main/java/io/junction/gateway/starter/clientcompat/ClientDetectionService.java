package io.junction.gateway.starter.clientcompat;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service for detecting which client adapter should be applied to a request.
 * Uses header-based detection rules from adapter configurations.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Service
public class ClientDetectionService {
    private static final Logger log = LoggerFactory.getLogger(ClientDetectionService.class);
    
    private final ClientAdapterLoader adapterLoader;
    
    @Autowired
    public ClientDetectionService(ClientAdapterLoader adapterLoader) {
        this.adapterLoader = adapterLoader;
    }
    
    /**
     * Detect the appropriate client adapter for the given request.
     * Returns the first matching adapter (First Match Wins strategy).
     * 
     * @param request the HTTP servlet request
     * @return the matching adapter configuration, or null if no match
     */
    public ClientAdapterConfig detectAdapter(HttpServletRequest request) {
        List<ClientAdapterConfig> configs = adapterLoader.getLoadedConfigs();
        
        for (ClientAdapterConfig config : configs) {
            if (matches(config, request)) {
                log.debug("Detected client adapter: {} for request", config.getId());
                return config;
            }
        }
        
        log.debug("No client adapter matched the request");
        return null;
    }
    
    /**
     * Check if a configuration matches the request
     * @param config the client adapter configuration to check
     * @param request the HTTP servlet request
     * @return true if the configuration matches the request, false otherwise
     */
    private boolean matches(ClientAdapterConfig config, HttpServletRequest request) {
        ClientAdapterConfig.DetectionConfig detection = config.getDetection();
        
        if (detection == null) {
            return false;
        }
        
        Map<String, String> headers = detection.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                String expectedValue = entry.getValue();
                String actualValue = request.getHeader(headerName);
                
                if (actualValue == null || !actualValue.equals(expectedValue)) {
                    return false;
                }
            }
        }
        
        Map<String, String> headerContains = detection.getHeaderContains();
        if (headerContains != null) {
            for (Map.Entry<String, String> entry : headerContains.entrySet()) {
                String headerName = entry.getKey();
                String expectedSubstring = entry.getValue();
                String actualValue = request.getHeader(headerName);
                
                if (actualValue == null || !actualValue.toLowerCase().contains(expectedSubstring.toLowerCase())) {
                    return false;
                }
            }
        }
        
        String versionRange = detection.getVersionRange();
        if (versionRange != null && !versionRange.isEmpty()) {
            String clientVersion = request.getHeader("x-client-version");
            if (!matchesVersionRange(clientVersion, versionRange)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Check if a version matches the specified range.
     * 
     * @param version the version to check (e.g., "3.1.4")
     * @param range the version range to match against (e.g., ">=3.0.0")
     * @return true if the version matches the range, false otherwise
     */
    private boolean matchesVersionRange(String version, String range) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        
        try {
            if (range.startsWith(">=")) {
                String minVersion = range.substring(2);
                return compareVersions(version, minVersion) >= 0;
            } else if (range.startsWith(">")) {
                String minVersion = range.substring(1);
                return compareVersions(version, minVersion) > 0;
            } else if (range.startsWith("<=")) {
                String maxVersion = range.substring(2);
                return compareVersions(version, maxVersion) <= 0;
            } else if (range.startsWith("<")) {
                String maxVersion = range.substring(1);
                return compareVersions(version, maxVersion) < 0;
            } else if (range.startsWith("=")) {
                String exactVersion = range.substring(1);
                return compareVersions(version, exactVersion) == 0;
            } else {
                return compareVersions(version, range) == 0;
            }
        } catch (Exception e) {
            log.warn("Error parsing version range '{}': {}", range, e.getMessage());
            return false;
        }
    }
    
    /**
     * Compare two semantic versions.
     * Returns: negative if v1 < v2, 0 if equal, positive if v1 > v2
     * 
     * @param v1 the first version (e.g., "3.1.4")
     * @param v2 the second version (e.g., "3.0.0")
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLength = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (num1 != num2) {
                return num1 - num2;
            }
        }
        
        return 0;
    }
    
    private int parseVersionPart(String part) {
        String numeric = part.replaceAll("-.*$", "");
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
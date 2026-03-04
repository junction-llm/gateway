package io.junction.gateway.starter.clientcompat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for client adapter system.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Component
@ConfigurationProperties(prefix = "junction.client-adapters")
public class ClientAdapterProperties {
    
    private boolean classpathEnabled = true;
    
    private String externalDirectory = "";
    
    private boolean hotReload = true;
    
    private boolean externalPriority = true;
    
    public boolean isClasspathEnabled() {
        return classpathEnabled;
    }
    
    public void setClasspathEnabled(boolean classpathEnabled) {
        this.classpathEnabled = classpathEnabled;
    }
    
    public String getExternalDirectory() {
        return externalDirectory;
    }
    
    public void setExternalDirectory(String externalDirectory) {
        this.externalDirectory = externalDirectory;
    }
    
    public boolean isHotReload() {
        return hotReload;
    }
    
    public void setHotReload(boolean hotReload) {
        this.hotReload = hotReload;
    }
    
    public boolean isExternalPriority() {
        return externalPriority;
    }
    
    public void setExternalPriority(boolean externalPriority) {
        this.externalPriority = externalPriority;
    }
}
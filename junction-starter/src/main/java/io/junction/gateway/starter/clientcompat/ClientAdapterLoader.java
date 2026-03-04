package io.junction.gateway.starter.clientcompat;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads client adapter configurations from classpath and external directory.
 * Supports hot-reloading of external configurations.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@Component
public class ClientAdapterLoader {
    private static final Logger log = LoggerFactory.getLogger(ClientAdapterLoader.class);
    private static final String CLASSPATH_LOCATION = "classpath:client-adapters/*.yaml";
    private static final String CLASSPATH_LOCATION_YML = "classpath:client-adapters/*.yml";
    
    private final ResourceLoader resourceLoader;
    private final ClientAdapterProperties properties;
    private final ObjectMapper yamlMapper;
    private final List<ClientAdapterConfig> loadedConfigs = new CopyOnWriteArrayList<>();
    private WatchService watchService;
    private Thread watchThread;
    
    @Autowired
    public ClientAdapterLoader(ResourceLoader resourceLoader, ClientAdapterProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.yamlMapper = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .build();
    }
    
    @PostConstruct
    public void init() {
        loadAllAdapters();
        
        if (properties.isHotReload() && !properties.getExternalDirectory().isEmpty()) {
            startFileWatcher();
        }
    }
    
    public void loadAllAdapters() {
        loadedConfigs.clear();
        
        if (properties.isClasspathEnabled()) {
            loadFromClasspath();
        }
        
        if (!properties.getExternalDirectory().isEmpty()) {
            loadFromExternalDirectory();
        }
        
        log.info("Loaded {} client adapter configurations", loadedConfigs.size());
    }
    
    private void loadFromClasspath() {
        ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        
        try {
            Resource[] yamlResources = resolver.getResources(CLASSPATH_LOCATION);
            for (Resource resource : yamlResources) {
                loadAdapterConfig(resource);
            }
        } catch (Exception e) {
            log.warn("Could not load classpath yaml adapter configurations: {}", e.getMessage());
        }
        
        try {
            Resource[] ymlResources = resolver.getResources(CLASSPATH_LOCATION_YML);
            for (Resource resource : ymlResources) {
                loadAdapterConfig(resource);
            }
        } catch (Exception e) {
            log.warn("Could not load classpath yml adapter configurations: {}", e.getMessage());
        }
    }
    
    private void loadFromExternalDirectory() {
        String externalDir = properties.getExternalDirectory();
        File dir = new File(externalDir);
        
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("External adapter directory does not exist: {}", externalDir);
            return;
        }
        
        File[] files = dir.listFiles((d, name) -> 
            name.endsWith(".yaml") || name.endsWith(".yml"));
        
        if (files == null || files.length == 0) {
            log.debug("No adapter configuration files found in: {}", externalDir);
            return;
        }
        
        for (File file : files) {
            try {
                ClientAdapterConfig config = yamlMapper.readValue(file, ClientAdapterConfig.class);
                if (validateConfig(config, file.getName())) {
                    if (properties.isExternalPriority()) {
                        // Remove any existing config with same ID
                        loadedConfigs.removeIf(c -> c.getId().equals(config.getId()));
                    }
                    loadedConfigs.add(config);
                    log.info("Loaded external adapter config: {} from {}", config.getId(), file.getName());
                }
            } catch (Exception e) {
                log.error("Failed to load adapter config from {}: {}", file.getName(), e.getMessage());
            }
        }
    }
    
    private void loadAdapterConfig(Resource resource) {
        try {
            ClientAdapterConfig config = yamlMapper.readValue(resource.getInputStream(), ClientAdapterConfig.class);
            if (validateConfig(config, resource.getFilename())) {
                loadedConfigs.add(config);
                log.info("Loaded classpath adapter config: {} from {}", config.getId(), resource.getFilename());
            }
        } catch (Exception e) {
            log.error("Failed to load adapter config from {}: {}", resource.getFilename(), e.getMessage());
        }
    }
    
    private boolean validateConfig(ClientAdapterConfig config, String filename) {
        if (config == null) {
            log.error("Invalid adapter config in {}: null configuration", filename);
            return false;
        }
        
        if (config.getId() == null || config.getId().isEmpty()) {
            log.error("Invalid adapter config in {}: missing 'id' field", filename);
            return false;
        }
        
        if (config.getDetection() == null) {
            log.error("Invalid adapter config in {}: missing 'detection' section", filename);
            return false;
        }
        
        return true;
    }
    
    private void startFileWatcher() {
        WatchService ws;
        try {
            ws = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.error("Failed to create watch service: {}", e.getMessage());
            return;
        }
        
        this.watchService = ws;
        Path path = Paths.get(properties.getExternalDirectory());
        
        if (!Files.exists(path)) {
            log.warn("Cannot start file watcher: directory does not exist: {}", path);
            return;
        }
        
        try {
            path.register(watchService, 
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.error("Failed to register file watcher on {}: {}", path, e.getMessage());
            return;
        }
        
        watchThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    WatchKey key = watchService.take();
                    boolean shouldReload = false;
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        String filename = changed.toString();
                        
                        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
                            log.debug("Detected change in adapter config: {}", filename);
                            shouldReload = true;
                        }
                    }
                    
                    if (shouldReload) {
                        log.info("Reloading adapter configurations due to file changes");
                        loadAllAdapters();
                    }
                    
                    key.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        log.info("Started file watcher for external adapter configurations");
    }
    
    public List<ClientAdapterConfig> getLoadedConfigs() {
        return Collections.unmodifiableList(loadedConfigs);
    }
    
    public void shutdown() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                log.warn("Error closing watch service: {}", e.getMessage());
            }
        }
    }
}
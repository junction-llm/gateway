package io.junction.gateway.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Junction Gateway.
 * 
 * <p>Supports provider configuration and security settings.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "junction")
public class JunctionProperties {
    
    private Providers providers = new Providers();
    private Security security = new Security();
    private ClientAdapters clientAdapters = new ClientAdapters();
    private Logging logging = new Logging();
    private Observability observability = new Observability();
    private Streaming streaming = new Streaming();
    
    
    public static class Providers {
        private Ollama ollama = new Ollama();
        private Gemini gemini = new Gemini();
        
        public Ollama getOllama() { return ollama; }
        public void setOllama(Ollama ollama) { this.ollama = ollama; }
        public Gemini getGemini() { return gemini; }
        public void setGemini(Gemini gemini) { this.gemini = gemini; }
    }
    
    public static class Ollama {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:11434";
        private String defaultModel = "kimi-k2.5:cloud";
        private int maxRemoteImageBytes = 20 * 1024 * 1024;
        private boolean allowPrivateRemoteImageUrls = false;
        private long connectTimeoutMillis = 10_000;
        private long requestTimeoutMillis = 300_000;
        private int maxConcurrentRequests = 100;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public int getMaxRemoteImageBytes() { return maxRemoteImageBytes; }
        public void setMaxRemoteImageBytes(int maxRemoteImageBytes) { this.maxRemoteImageBytes = maxRemoteImageBytes; }
        public boolean isAllowPrivateRemoteImageUrls() { return allowPrivateRemoteImageUrls; }
        public void setAllowPrivateRemoteImageUrls(boolean allowPrivateRemoteImageUrls) { this.allowPrivateRemoteImageUrls = allowPrivateRemoteImageUrls; }
        public long getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(long connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
        public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public void setRequestTimeoutMillis(long requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
    }
    
    public static class Gemini {
        private boolean enabled = false;
        private String apiKey;
        private String model = "gemini-1.5-flash";
        private long connectTimeoutMillis = 10_000;
        private long requestTimeoutMillis = 300_000;
        private int maxConcurrentRequests = 100;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(long connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
        public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
        public void setRequestTimeoutMillis(long requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
    }
    
    
    public static class Streaming {
        /** SSE emitter timeout in milliseconds; zero or negative disables the timeout */
        private long sseTimeoutMillis = 300_000;

        /** Maximum concurrently active SSE streams; zero or negative disables admission limiting */
        private int maxActiveStreams = 1_000;

        /** Maximum provider-stream idle time between chunks in milliseconds; zero or negative disables the idle timeout */
        private long streamIdleTimeoutMillis = 60_000;

        public long getSseTimeoutMillis() { return sseTimeoutMillis; }
        public void setSseTimeoutMillis(long sseTimeoutMillis) { this.sseTimeoutMillis = sseTimeoutMillis; }

        public int getMaxActiveStreams() { return maxActiveStreams; }
        public void setMaxActiveStreams(int maxActiveStreams) { this.maxActiveStreams = maxActiveStreams; }

        public long getStreamIdleTimeoutMillis() { return streamIdleTimeoutMillis; }
        public void setStreamIdleTimeoutMillis(long streamIdleTimeoutMillis) { this.streamIdleTimeoutMillis = streamIdleTimeoutMillis; }
    }

    public static class Security {
        private ApiKey apiKey = new ApiKey();
        private IpRateLimit ipRateLimit = new IpRateLimit();
        private IpWhitelist ipWhitelist = new IpWhitelist();
        
        public ApiKey getApiKey() { return apiKey; }
        public void setApiKey(ApiKey apiKey) { this.apiKey = apiKey; }
        
        public IpRateLimit getIpRateLimit() { return ipRateLimit; }
        public void setIpRateLimit(IpRateLimit ipRateLimit) { this.ipRateLimit = ipRateLimit; }
        
        public IpWhitelist getIpWhitelist() { return ipWhitelist; }
        public void setIpWhitelist(IpWhitelist ipWhitelist) { this.ipWhitelist = ipWhitelist; }
    }
    
    public static class ApiKey {
        /** Whether API key authentication is required */
        private boolean required = true;
        
        /** Storage type: memory, file, h2, postgresql */
        private String storage = "memory";
        
        /** File path for the YAML storage backend */
        private String filePath = "${JUNCTION_API_KEYS_FILE:api-keys.yml}";
        
        /** H2 database URL for JDBC-backed API-key storage */
        private String h2Url = "jdbc:h2:file:${JUNCTION_H2_PATH:./data/junction};DB_CLOSE_DELAY=-1";
        
        /** H2 username */
        private String h2Username = "sa";
        
        /** H2 password */
        private String h2Password = "";
        
        /** PostgreSQL URL for JDBC-backed API-key storage */
        private String postgresqlUrl = "${JUNCTION_POSTGRES_URL:}";
        
        /** PostgreSQL username */
        private String postgresqlUsername = "${JUNCTION_POSTGRES_USER:}";
        
        /** PostgreSQL password */
        private String postgresqlPassword = "${JUNCTION_POSTGRES_PASSWORD:}";

        /** Maximum connections in the API-key JDBC pool */
        private int jdbcPoolMaximumPoolSize = 10;

        /** Minimum idle connections retained in the API-key JDBC pool */
        private int jdbcPoolMinimumIdle = 0;

        /** Maximum time in milliseconds to wait for an API-key JDBC connection */
        private long jdbcPoolConnectionTimeoutMillis = 30_000;

        /** Maximum time in milliseconds an idle API-key JDBC connection may remain in the pool */
        private long jdbcPoolIdleTimeoutMillis = 600_000;

        /** Maximum lifetime in milliseconds for API-key JDBC connections */
        private long jdbcPoolMaxLifetimeMillis = 1_800_000;

        /** Maximum time in milliseconds Hikari waits to initialize the API-key JDBC pool; use 0 to validate without fail-fast connection attempts */
        private long jdbcPoolInitializationFailTimeoutMillis = 1;
        
        /** Startup seed API keys added when missing across all storage backends */
        private List<PreconfiguredKey> preconfigured = new ArrayList<>();

        /** Usage recorder mode: sync, async, or noop */
        private String usageRecorder = "async";

        /** Maximum distinct API keys pending async usage flush */
        private int usageRecorderMaxPendingKeys = 10_000;

        /** Async usage recorder flush interval in milliseconds */
        private long usageRecorderFlushIntervalMillis = 5_000;

        /** Async usage recorder shutdown flush timeout in milliseconds */
        private long usageRecorderShutdownTimeoutMillis = 2_000;
        
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        
        public String getStorage() { return storage; }
        public void setStorage(String storage) { this.storage = storage; }
        
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        
        public String getH2Url() { return h2Url; }
        public void setH2Url(String h2Url) { this.h2Url = h2Url; }
        
        public String getH2Username() { return h2Username; }
        public void setH2Username(String h2Username) { this.h2Username = h2Username; }
        
        public String getH2Password() { return h2Password; }
        public void setH2Password(String h2Password) { this.h2Password = h2Password; }
        
        public String getPostgresqlUrl() { return postgresqlUrl; }
        public void setPostgresqlUrl(String postgresqlUrl) { this.postgresqlUrl = postgresqlUrl; }
        
        public String getPostgresqlUsername() { return postgresqlUsername; }
        public void setPostgresqlUsername(String postgresqlUsername) { this.postgresqlUsername = postgresqlUsername; }
        
        public String getPostgresqlPassword() { return postgresqlPassword; }
        public void setPostgresqlPassword(String postgresqlPassword) { this.postgresqlPassword = postgresqlPassword; }

        public int getJdbcPoolMaximumPoolSize() { return jdbcPoolMaximumPoolSize; }
        public void setJdbcPoolMaximumPoolSize(int jdbcPoolMaximumPoolSize) { this.jdbcPoolMaximumPoolSize = jdbcPoolMaximumPoolSize; }

        public int getJdbcPoolMinimumIdle() { return jdbcPoolMinimumIdle; }
        public void setJdbcPoolMinimumIdle(int jdbcPoolMinimumIdle) { this.jdbcPoolMinimumIdle = jdbcPoolMinimumIdle; }

        public long getJdbcPoolConnectionTimeoutMillis() { return jdbcPoolConnectionTimeoutMillis; }
        public void setJdbcPoolConnectionTimeoutMillis(long jdbcPoolConnectionTimeoutMillis) { this.jdbcPoolConnectionTimeoutMillis = jdbcPoolConnectionTimeoutMillis; }

        public long getJdbcPoolIdleTimeoutMillis() { return jdbcPoolIdleTimeoutMillis; }
        public void setJdbcPoolIdleTimeoutMillis(long jdbcPoolIdleTimeoutMillis) { this.jdbcPoolIdleTimeoutMillis = jdbcPoolIdleTimeoutMillis; }

        public long getJdbcPoolMaxLifetimeMillis() { return jdbcPoolMaxLifetimeMillis; }
        public void setJdbcPoolMaxLifetimeMillis(long jdbcPoolMaxLifetimeMillis) { this.jdbcPoolMaxLifetimeMillis = jdbcPoolMaxLifetimeMillis; }

        public long getJdbcPoolInitializationFailTimeoutMillis() { return jdbcPoolInitializationFailTimeoutMillis; }
        public void setJdbcPoolInitializationFailTimeoutMillis(long jdbcPoolInitializationFailTimeoutMillis) { this.jdbcPoolInitializationFailTimeoutMillis = jdbcPoolInitializationFailTimeoutMillis; }
        
        public List<PreconfiguredKey> getPreconfigured() { return preconfigured; }
        public void setPreconfigured(List<PreconfiguredKey> preconfigured) { this.preconfigured = preconfigured; }

        public String getUsageRecorder() { return usageRecorder; }
        public void setUsageRecorder(String usageRecorder) { this.usageRecorder = usageRecorder; }

        public int getUsageRecorderMaxPendingKeys() { return usageRecorderMaxPendingKeys; }
        public void setUsageRecorderMaxPendingKeys(int usageRecorderMaxPendingKeys) { this.usageRecorderMaxPendingKeys = usageRecorderMaxPendingKeys; }

        public long getUsageRecorderFlushIntervalMillis() { return usageRecorderFlushIntervalMillis; }
        public void setUsageRecorderFlushIntervalMillis(long usageRecorderFlushIntervalMillis) { this.usageRecorderFlushIntervalMillis = usageRecorderFlushIntervalMillis; }

        public long getUsageRecorderShutdownTimeoutMillis() { return usageRecorderShutdownTimeoutMillis; }
        public void setUsageRecorderShutdownTimeoutMillis(long usageRecorderShutdownTimeoutMillis) { this.usageRecorderShutdownTimeoutMillis = usageRecorderShutdownTimeoutMillis; }
    }
    
    public static class PreconfiguredKey {
        private String key;
        private String name = "Default Key";
        private String tier = "ENTERPRISE";
        
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
    }
    
    
    public static class IpRateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
        private int requestsPerHour = 1000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
        
        public int getRequestsPerHour() { return requestsPerHour; }
        public void setRequestsPerHour(int requestsPerHour) { this.requestsPerHour = requestsPerHour; }
    }
    
    
    public static class IpWhitelist {
        private boolean enabled = false;
        private String allowedIps = "${JUNCTION_IP_WHITELIST:}";
        private boolean allowPrivateIps = true;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public String getAllowedIps() { return allowedIps; }
        public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }
        
        public boolean isAllowPrivateIps() { return allowPrivateIps; }
        public void setAllowPrivateIps(boolean allowPrivateIps) { this.allowPrivateIps = allowPrivateIps; }
    }
    
    
    public static class ClientAdapters {
        private boolean classpathEnabled = true;
        private String externalDirectory = "${JUNCTION_CLIENT_ADAPTERS_DIR:}";
        private boolean hotReload = true;
        private boolean externalPriority = true;
        
        public boolean isClasspathEnabled() { return classpathEnabled; }
        public void setClasspathEnabled(boolean classpathEnabled) { this.classpathEnabled = classpathEnabled; }
        
        public String getExternalDirectory() { return externalDirectory; }
        public void setExternalDirectory(String externalDirectory) { this.externalDirectory = externalDirectory; }
        
        public boolean isHotReload() { return hotReload; }
        public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }
        
        public boolean isExternalPriority() { return externalPriority; }
        public void setExternalPriority(boolean externalPriority) { this.externalPriority = externalPriority; }
    }


    public static class Logging {
        private ChatResponse chatResponse = new ChatResponse();

        public ChatResponse getChatResponse() { return chatResponse; }
        public void setChatResponse(ChatResponse chatResponse) { this.chatResponse = chatResponse; }
    }


    public static class ChatResponse {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Observability {
        private Admin admin = new Admin();
        private ManagementSecurity security = new ManagementSecurity();

        public Admin getAdmin() { return admin; }
        public void setAdmin(Admin admin) { this.admin = admin; }

        public ManagementSecurity getSecurity() { return security; }
        public void setSecurity(ManagementSecurity security) { this.security = security; }
    }

    public static class Admin {
        private boolean cacheWriteEnabled = false;

        public boolean isCacheWriteEnabled() { return cacheWriteEnabled; }
        public void setCacheWriteEnabled(boolean cacheWriteEnabled) { this.cacheWriteEnabled = cacheWriteEnabled; }
    }

    public static class ManagementSecurity {
        private boolean enabled = true;
        private boolean publicHealthEnabled = true;
        private String username = "actuator";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isPublicHealthEnabled() { return publicHealthEnabled; }
        public void setPublicHealthEnabled(boolean publicHealthEnabled) { this.publicHealthEnabled = publicHealthEnabled; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
    
    
    public Providers getProviders() { return providers; }
    public void setProviders(Providers providers) { this.providers = providers; }
    
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    
    public ClientAdapters getClientAdapters() { return clientAdapters; }
    public void setClientAdapters(ClientAdapters clientAdapters) { this.clientAdapters = clientAdapters; }

    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }

    public Observability getObservability() { return observability; }
    public void setObservability(Observability observability) { this.observability = observability; }

    public Streaming getStreaming() { return streaming; }
    public void setStreaming(Streaming streaming) { this.streaming = streaming; }
    
    
    public Ollama getOllama() { return providers.getOllama(); }
    public Gemini getGemini() { return providers.getGemini(); }
    public ApiKey getApiKeyConfig() { return security.getApiKey(); }
}

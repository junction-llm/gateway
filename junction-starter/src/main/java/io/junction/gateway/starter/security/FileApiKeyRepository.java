package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import io.junction.gateway.core.security.InMemoryApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * YAML-backed API-key repository intended for single-node deployments.
 * 
 * @author Juan Hidalgo
 * @since 0.0.4
 */
public final class FileApiKeyRepository implements ApiKeyRepository {
    private static final Logger log = LoggerFactory.getLogger(FileApiKeyRepository.class);

    private final Path storagePath;
    private final YAMLMapper yamlMapper;
    private final InMemoryApiKeyRepository delegate = new InMemoryApiKeyRepository();

    public FileApiKeyRepository(String filePath) {
        Objects.requireNonNull(filePath, "filePath cannot be null");
        this.storagePath = Path.of(filePath).toAbsolutePath().normalize();
        this.yamlMapper = YAMLMapper.builder().build();
        loadFromDisk();
    }

    @Override
    public synchronized ApiKey save(ApiKey apiKey) {
        try {
            ApiKey saved = delegate.save(apiKey);
            writeSnapshot();
            return saved;
        } catch (RuntimeException e) {
            throw storageFailure("Failed to save API key to file storage", e);
        }
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return delegate.findByKeyHash(keyHash);
    }

    @Override
    public Optional<ApiKey> findByKeyPrefix(String keyPrefix) {
        return delegate.findByKeyPrefix(keyPrefix);
    }

    @Override
    public List<ApiKey> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<ApiKey> findByStatus(ApiKey.Status status) {
        return delegate.findByStatus(status);
    }

    @Override
    public List<ApiKey> findByTier(ApiKey.Tier tier) {
        return delegate.findByTier(tier);
    }

    @Override
    public synchronized boolean deleteById(String id) {
        try {
            boolean deleted = delegate.deleteById(id);
            if (deleted) {
                writeSnapshot();
            }
            return deleted;
        } catch (RuntimeException e) {
            throw storageFailure("Failed to delete API key from file storage", e);
        }
    }

    @Override
    public boolean existsByKeyHash(String keyHash) {
        return delegate.existsByKeyHash(keyHash);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public long countByStatus(ApiKey.Status status) {
        return delegate.countByStatus(status);
    }

    @Override
    public synchronized ApiKey incrementUsage(String id) {
        try {
            ApiKey updated = delegate.incrementUsage(id);
            writeSnapshot();
            return updated;
        } catch (ApiKeyNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            throw storageFailure("Failed to update API key usage in file storage", e);
        }
    }

    private synchronized void loadFromDisk() {
        delegate.clear();

        if (!Files.exists(storagePath)) {
            log.info("API key file storage will be created on first write: {}", storagePath);
            return;
        }

        try {
            if (Files.size(storagePath) == 0L) {
                log.info("API key file storage is empty: {}", storagePath);
                return;
            }

            StorageDocument document = yamlMapper.readValue(storagePath.toFile(), StorageDocument.class);
            if (document == null || document.getKeys() == null) {
                return;
            }

            for (StoredApiKey storedApiKey : document.getKeys()) {
                delegate.save(toApiKey(storedApiKey));
            }

            log.info("Loaded {} API keys from file storage at {}", delegate.count(), storagePath);
        } catch (IOException e) {
            throw storageFailure("Failed to load API keys from file storage", e);
        }
    }

    private void writeSnapshot() {
        try {
            Path parentDirectory = storagePath.getParent();
            if (parentDirectory != null) {
                Files.createDirectories(parentDirectory);
            }

            Path tempFile = Files.createTempFile(
                parentDirectory != null ? parentDirectory : storagePath.getParent(),
                storagePath.getFileName().toString(),
                ".tmp"
            );

            try {
                StorageDocument document = new StorageDocument();
                document.setKeys(delegate.findAll().stream()
                    .sorted(Comparator.comparing(ApiKey::id))
                    .map(this::toStoredApiKey)
                    .toList());

                yamlMapper.writeValue(tempFile.toFile(), document);
                moveAtomically(tempFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw storageFailure("Failed to persist API keys to file storage", e);
        }
    }

    private void moveAtomically(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private StoredApiKey toStoredApiKey(ApiKey apiKey) {
        StoredApiKey storedApiKey = new StoredApiKey();
        storedApiKey.setId(apiKey.id());
        storedApiKey.setKeyHash(apiKey.keyHash());
        storedApiKey.setKeyPrefix(apiKey.keyPrefix());
        storedApiKey.setName(apiKey.name());
        storedApiKey.setDescription(apiKey.description());
        storedApiKey.setTier(apiKey.tier().name());
        storedApiKey.setStatus(apiKey.status().name());
        storedApiKey.setAllowedModels(new LinkedHashSet<>(apiKey.allowedModels()));
        storedApiKey.setAllowedIps(new LinkedHashSet<>(apiKey.allowedIps()));
        storedApiKey.setCreatedAt(formatInstant(apiKey.createdAt()));
        storedApiKey.setExpiresAt(formatInstant(apiKey.expiresAt()));
        storedApiKey.setLastUsedAt(formatInstant(apiKey.lastUsedAt()));
        storedApiKey.setRequestCount(apiKey.requestCount());
        storedApiKey.setCreatedBy(apiKey.createdBy());
        storedApiKey.setMetadata(StoredMetadata.from(apiKey.metadata()));
        return storedApiKey;
    }

    private ApiKey toApiKey(StoredApiKey storedApiKey) {
        return new ApiKey.Builder()
            .id(storedApiKey.getId())
            .keyHash(storedApiKey.getKeyHash())
            .keyPrefix(storedApiKey.getKeyPrefix())
            .name(storedApiKey.getName())
            .description(storedApiKey.getDescription())
            .tier(ApiKey.Tier.valueOf(storedApiKey.getTier()))
            .status(ApiKey.Status.valueOf(storedApiKey.getStatus()))
            .allowedModels(asSet(storedApiKey.getAllowedModels()))
            .allowedIps(asSet(storedApiKey.getAllowedIps()))
            .createdAt(parseInstant(storedApiKey.getCreatedAt()))
            .expiresAt(parseInstant(storedApiKey.getExpiresAt()))
            .lastUsedAt(parseInstant(storedApiKey.getLastUsedAt()))
            .requestCount(storedApiKey.getRequestCount())
            .createdBy(storedApiKey.getCreatedBy())
            .metadata(storedApiKey.getMetadata() != null ? storedApiKey.getMetadata().toMetadata() : new ApiKey.Metadata())
            .build();
    }

    private Set<String> asSet(Set<String> values) {
        return values != null ? Set.copyOf(values) : Set.of();
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private Instant parseInstant(String value) {
        return value != null && !value.isBlank() ? Instant.parse(value) : null;
    }

    private ApiKeyStorageException storageFailure(String message, Exception cause) {
        return new ApiKeyStorageException(message + " (" + storagePath + ")", cause);
    }

    public static final class StorageDocument {
        private List<StoredApiKey> keys = new ArrayList<>();

        public List<StoredApiKey> getKeys() {
            return keys;
        }

        public void setKeys(List<StoredApiKey> keys) {
            this.keys = keys != null ? keys : new ArrayList<>();
        }
    }

    public static final class StoredApiKey {
        private String id;
        private String keyHash;
        private String keyPrefix;
        private String name;
        private String description;
        private String tier;
        private String status;
        private Set<String> allowedModels = new LinkedHashSet<>();
        private Set<String> allowedIps = new LinkedHashSet<>();
        private String createdAt;
        private String expiresAt;
        private String lastUsedAt;
        private long requestCount;
        private String createdBy;
        private StoredMetadata metadata = new StoredMetadata();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getKeyHash() { return keyHash; }
        public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Set<String> getAllowedModels() { return allowedModels; }
        public void setAllowedModels(Set<String> allowedModels) {
            this.allowedModels = allowedModels != null ? allowedModels : new LinkedHashSet<>();
        }
        public Set<String> getAllowedIps() { return allowedIps; }
        public void setAllowedIps(Set<String> allowedIps) {
            this.allowedIps = allowedIps != null ? allowedIps : new LinkedHashSet<>();
        }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getExpiresAt() { return expiresAt; }
        public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
        public String getLastUsedAt() { return lastUsedAt; }
        public void setLastUsedAt(String lastUsedAt) { this.lastUsedAt = lastUsedAt; }
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
        public StoredMetadata getMetadata() { return metadata; }
        public void setMetadata(StoredMetadata metadata) { this.metadata = metadata != null ? metadata : new StoredMetadata(); }
    }

    public static final class StoredMetadata {
        private String organization;
        private String contactEmail;
        private String webhookUrl;
        private Map<String, String> customFields = new HashMap<>();

        static StoredMetadata from(ApiKey.Metadata metadata) {
            StoredMetadata storedMetadata = new StoredMetadata();
            storedMetadata.setOrganization(metadata.organization());
            storedMetadata.setContactEmail(metadata.contactEmail());
            storedMetadata.setWebhookUrl(metadata.webhookUrl());
            storedMetadata.setCustomFields(metadata.customFields());
            return storedMetadata;
        }

        ApiKey.Metadata toMetadata() {
            return new ApiKey.Metadata(organization, contactEmail, webhookUrl, customFields);
        }

        public String getOrganization() { return organization; }
        public void setOrganization(String organization) { this.organization = organization; }
        public String getContactEmail() { return contactEmail; }
        public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public Map<String, String> getCustomFields() { return customFields; }
        public void setCustomFields(Map<String, String> customFields) {
            this.customFields = customFields != null ? customFields : new HashMap<>();
        }
    }
}

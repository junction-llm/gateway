package io.junction.gateway.starter.security;

import io.junction.gateway.core.security.ApiKey;
import io.junction.gateway.core.security.ApiKeyRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC-backed API-key repository for H2 and PostgreSQL.
 * 
 * @author Juan Hidalgo
 * @since 0.0.4
 */
public final class JdbcApiKeyRepository implements ApiKeyRepository {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String SCHEMA_RESOURCE_PATH = "sql/junction-api-key-schema.sql";

    private static final String TABLE_NAME = "junction_api_keys";
    private static final String BASE_SELECT = """
        SELECT id, key_hash, key_prefix, name, description, tier, status,
               allowed_models_json, allowed_ips_json, created_at, expires_at,
               last_used_at, request_count, created_by, metadata_json
        FROM junction_api_keys
        """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ApiKey> rowMapper = this::mapRow;

    public JdbcApiKeyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
        initializeSchema();
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        Objects.requireNonNull(apiKey, "apiKey cannot be null");
        try {
            int updated = jdbcTemplate.update("""
                UPDATE junction_api_keys
                SET key_hash = ?, key_prefix = ?, name = ?, description = ?, tier = ?, status = ?,
                    allowed_models_json = ?, allowed_ips_json = ?, created_at = ?, expires_at = ?,
                    last_used_at = ?, request_count = ?, created_by = ?, metadata_json = ?
                WHERE id = ?
                """,
                apiKey.keyHash(),
                apiKey.keyPrefix(),
                apiKey.name(),
                apiKey.description(),
                apiKey.tier().name(),
                apiKey.status().name(),
                toJson(apiKey.allowedModels()),
                toJson(apiKey.allowedIps()),
                toEpochMillis(apiKey.createdAt()),
                toEpochMillis(apiKey.expiresAt()),
                toEpochMillis(apiKey.lastUsedAt()),
                apiKey.requestCount(),
                apiKey.createdBy(),
                toJson(metadataDocument(apiKey)),
                apiKey.id()
            );

            if (updated == 0) {
                insert(apiKey);
            }

            return apiKey;
        } catch (DataAccessException e) {
            throw new ApiKeyStorageException("Failed to save API key to JDBC storage", e);
        }
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return queryForOptional(BASE_SELECT + " WHERE id = ?", id);
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return queryForOptional(BASE_SELECT + " WHERE key_hash = ?", keyHash);
    }

    @Override
    public Optional<ApiKey> findByKeyPrefix(String keyPrefix) {
        return queryForOptional(BASE_SELECT + " WHERE key_prefix = ?", keyPrefix);
    }

    @Override
    public List<ApiKey> findAll() {
        return jdbcTemplate.query(BASE_SELECT + " ORDER BY created_at ASC, id ASC", rowMapper);
    }

    @Override
    public List<ApiKey> findByStatus(ApiKey.Status status) {
        return jdbcTemplate.query(BASE_SELECT + " WHERE status = ? ORDER BY created_at ASC, id ASC", rowMapper, status.name());
    }

    @Override
    public List<ApiKey> findByTier(ApiKey.Tier tier) {
        return jdbcTemplate.query(BASE_SELECT + " WHERE tier = ? ORDER BY created_at ASC, id ASC", rowMapper, tier.name());
    }

    @Override
    public boolean deleteById(String id) {
        try {
            return jdbcTemplate.update("DELETE FROM " + TABLE_NAME + " WHERE id = ?", id) > 0;
        } catch (DataAccessException e) {
            throw new ApiKeyStorageException("Failed to delete API key from JDBC storage", e);
        }
    }

    @Override
    public boolean existsByKeyHash(String keyHash) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE key_hash = ?",
            Long.class,
            keyHash
        );
        return count != null && count > 0;
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE_NAME, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countByStatus(ApiKey.Status status) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE status = ?",
            Long.class,
            status.name()
        );
        return count != null ? count : 0L;
    }

    @Override
    public ApiKey incrementUsage(String id) {
        try {
            long now = Instant.now().toEpochMilli();
            int updated = jdbcTemplate.update("""
                UPDATE junction_api_keys
                SET request_count = request_count + 1, last_used_at = ?
                WHERE id = ?
                """,
                now,
                id
            );

            if (updated == 0) {
                throw new ApiKeyNotFoundException(id);
            }

            return findById(id).orElseThrow(() -> new ApiKeyNotFoundException(id));
        } catch (ApiKeyNotFoundException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new ApiKeyStorageException("Failed to update API key usage in JDBC storage", e);
        }
    }

    private void initializeSchema() {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(SCHEMA_RESOURCE_PATH));
            populator.execute(dataSource);
        } catch (Exception e) {
            throw new ApiKeyStorageException("Failed to initialize JDBC API key schema", e);
        }
    }

    private void insert(ApiKey apiKey) {
        try {
            jdbcTemplate.update("""
                INSERT INTO junction_api_keys (
                    id, key_hash, key_prefix, name, description, tier, status,
                    allowed_models_json, allowed_ips_json, created_at, expires_at,
                    last_used_at, request_count, created_by, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                apiKey.id(),
                apiKey.keyHash(),
                apiKey.keyPrefix(),
                apiKey.name(),
                apiKey.description(),
                apiKey.tier().name(),
                apiKey.status().name(),
                toJson(apiKey.allowedModels()),
                toJson(apiKey.allowedIps()),
                toEpochMillis(apiKey.createdAt()),
                toEpochMillis(apiKey.expiresAt()),
                toEpochMillis(apiKey.lastUsedAt()),
                apiKey.requestCount(),
                apiKey.createdBy(),
                toJson(metadataDocument(apiKey))
            );
        } catch (DataIntegrityViolationException e) {
            jdbcTemplate.update("""
                UPDATE junction_api_keys
                SET key_hash = ?, key_prefix = ?, name = ?, description = ?, tier = ?, status = ?,
                    allowed_models_json = ?, allowed_ips_json = ?, created_at = ?, expires_at = ?,
                    last_used_at = ?, request_count = ?, created_by = ?, metadata_json = ?
                WHERE id = ?
                """,
                apiKey.keyHash(),
                apiKey.keyPrefix(),
                apiKey.name(),
                apiKey.description(),
                apiKey.tier().name(),
                apiKey.status().name(),
                toJson(apiKey.allowedModels()),
                toJson(apiKey.allowedIps()),
                toEpochMillis(apiKey.createdAt()),
                toEpochMillis(apiKey.expiresAt()),
                toEpochMillis(apiKey.lastUsedAt()),
                apiKey.requestCount(),
                apiKey.createdBy(),
                toJson(metadataDocument(apiKey)),
                apiKey.id()
            );
        }
    }

    private Optional<ApiKey> queryForOptional(String sql, Object value) {
        List<ApiKey> results = jdbcTemplate.query(sql, rowMapper, value);
        return results.stream().findFirst();
    }

    private ApiKey mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ApiKey.Builder()
            .id(rs.getString("id"))
            .keyHash(rs.getString("key_hash"))
            .keyPrefix(rs.getString("key_prefix"))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .tier(ApiKey.Tier.valueOf(rs.getString("tier")))
            .status(ApiKey.Status.valueOf(rs.getString("status")))
            .allowedModels(readStringSet(rs.getString("allowed_models_json")))
            .allowedIps(readStringSet(rs.getString("allowed_ips_json")))
            .createdAt(toInstant(rs.getLong("created_at")))
            .expiresAt(nullableInstant(rs, "expires_at"))
            .lastUsedAt(nullableInstant(rs, "last_used_at"))
            .requestCount(rs.getLong("request_count"))
            .createdBy(rs.getString("created_by"))
            .metadata(readMetadata(rs.getString("metadata_json")))
            .build();
    }

    private ApiKey.Metadata readMetadata(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = JSON_MAPPER.readValue(json, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> customFields = metadata.get("customFields") instanceof Map<?, ?> map
                ? (Map<String, String>) map
                : Map.of();
            return new ApiKey.Metadata(
                asString(metadata.get("organization")),
                asString(metadata.get("contactEmail")),
                asString(metadata.get("webhookUrl")),
                customFields
            );
        } catch (Exception e) {
            throw new ApiKeyStorageException("Failed to deserialize API key metadata from JDBC storage", e);
        }
    }

    private Set<String> readStringSet(String json) {
        try {
            List<String> values = JSON_MAPPER.readValue(
                json,
                JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)
            );
            return values != null ? Set.copyOf(values) : Set.of();
        } catch (Exception e) {
            throw new ApiKeyStorageException("Failed to deserialize API key set values from JDBC storage", e);
        }
    }

    private String toJson(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiKeyStorageException("Failed to serialize API key data for JDBC storage", e);
        }
    }

    private Map<String, Object> metadataDocument(ApiKey apiKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("organization", apiKey.metadata().organization());
        metadata.put("contactEmail", apiKey.metadata().contactEmail());
        metadata.put("webhookUrl", apiKey.metadata().webhookUrl());
        metadata.put("customFields", apiKey.metadata().customFields());
        return metadata;
    }

    private Long toEpochMillis(Instant instant) {
        return instant != null ? instant.toEpochMilli() : null;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        long epochMillis = rs.getLong(column);
        if (rs.wasNull()) {
            return null;
        }
        return toInstant(epochMillis);
    }

    private Instant toInstant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}

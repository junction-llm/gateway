package io.junction.gateway.core.security;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing API keys. Provides methods for CRUD operations and querying API keys based on various criteria.
 * Implementations of this interface can use different storage backends (e.g., in-memory, database, etc.) while adhering to the defined contract.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public interface ApiKeyRepository {
    
    /**
     * Saves a new API key or updates an existing one.
     * 
     * @param apiKey the API key to save
     * @return the saved API key (may have generated fields populated)
     * @throws ApiKeyStorageException if storage operation fails
     */
    ApiKey save(ApiKey apiKey);
    
    /**
     * Finds an API key by its unique identifier.
     * 
     * @param id the API key ID
     * @return optional containing the API key if found
     */
    Optional<ApiKey> findById(String id);
    
    /**
     * Finds an API key by its hash.
     * 
     * @param keyHash the hashed API key
     * @return optional containing the API key if found
     */
    Optional<ApiKey> findByKeyHash(String keyHash);
    
    /**
     * Finds an API key by its prefix (first 8 characters of the raw key).
     * Useful for identifying keys without exposing the full hash.
     * 
     * @param keyPrefix the key prefix
     * @return optional containing the API key if found
     */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
    
    /**
     * Retrieves all API keys.
     * 
     * @return list of all API keys
     */
    List<ApiKey> findAll();
    
    /**
     * Retrieves all API keys with a specific status.
     * 
     * @param status the status to filter by
     * @return list of matching API keys
     */
    List<ApiKey> findByStatus(ApiKey.Status status);
    
    /**
     * Retrieves all API keys with a specific tier.
     * 
     * @param tier the tier to filter by
     * @return list of matching API keys
     */
    List<ApiKey> findByTier(ApiKey.Tier tier);
    
    /**
     * Deletes an API key by its ID.
     * 
     * @param id the API key ID
     * @return true if deleted, false if not found
     */
    boolean deleteById(String id);
    
    /**
     * Checks if an API key exists with the given hash.
     * 
     * @param keyHash the hashed API key
     * @return true if exists
     */
    boolean existsByKeyHash(String keyHash);
    
    /**
     * Counts total number of API keys.
     * 
     * @return total count
     */
    long count();
    
    /**
     * Counts API keys by status.
     * 
     * @param status the status to count
     * @return count of matching keys
     */
    long countByStatus(ApiKey.Status status);
    
    /**
     * Updates the usage statistics for an API key atomically.
     * 
     * @param id the API key ID
     * @return the updated API key
     * @throws ApiKeyNotFoundException if key not found
     */
    ApiKey incrementUsage(String id);
    
    /**
    * Revokes an API key by setting its status to REVOKED.
    * 
    * @param id the API key ID
    * @return the revoked API key
    * @throws ApiKeyNotFoundException if key not found
    */
    class ApiKeyStorageException extends RuntimeException {
        public ApiKeyStorageException(String message) {
            super(message);
        }
        
        public ApiKeyStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Exception thrown when an API key is not found in the repository.
     *
     * @param id the ID of the API key that was not found
     * @return a new ApiKeyNotFoundException with a descriptive message
     */
    class ApiKeyNotFoundException extends RuntimeException {
        public ApiKeyNotFoundException(String id) {
            super("API key not found: " + id);
        }
    }
}

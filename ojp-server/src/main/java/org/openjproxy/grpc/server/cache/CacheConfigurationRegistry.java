package org.openjproxy.grpc.server.cache;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for cache configurations indexed by datasource name.
 * 
 * <p>This class provides a centralized store for parsed cache configurations.
 * Configurations are loaded lazily from System properties on first access
 * and cached for subsequent requests.
 *
 * <p>Thread-safe singleton.
 */
public final class CacheConfigurationRegistry {
    
    private static final CacheConfigurationRegistry INSTANCE = new CacheConfigurationRegistry();
    
    private final ConcurrentHashMap<String, CacheConfiguration> configurations = new ConcurrentHashMap<>();
    
    private CacheConfigurationRegistry() {
        // Private constructor for singleton
    }
    
    /**
     * Get the singleton instance.
     *
     * @return the registry instance
     */
    public static CacheConfigurationRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Get cache configuration for a datasource, loading from properties if not cached.
     * 
     * <p>If the configuration has not been loaded yet, it will be parsed from
     * System properties and cached. Subsequent calls will return the cached instance.
     *
     * @param datasourceName the datasource name (must not be null)
     * @return the cache configuration (never null)
     * @throws IllegalArgumentException if datasourceName is null or configuration is invalid
     */
    public CacheConfiguration getOrLoad(String datasourceName) {
        Objects.requireNonNull(datasourceName, "datasourceName cannot be null");
        
        return configurations.computeIfAbsent(
            datasourceName,
            CacheConfigurationParser::parse
        );
    }
    
    /**
     * Reload configuration for a specific datasource.
     * 
     * <p>This removes the cached configuration and forces it to be re-parsed
     * from System properties on the next access. Useful for testing or
     * if properties are changed at runtime.
     *
     * @param datasourceName the datasource name
     * @return the newly loaded configuration
     * @throws IllegalArgumentException if datasourceName is null or configuration is invalid
     */
    public CacheConfiguration reload(String datasourceName) {
        Objects.requireNonNull(datasourceName, "datasourceName cannot be null");
        
        configurations.remove(datasourceName);
        return getOrLoad(datasourceName);
    }
    
    /**
     * Check if a configuration is cached for the given datasource.
     *
     * @param datasourceName the datasource name
     * @return true if configuration is cached, false otherwise
     */
    public boolean isCached(String datasourceName) {
        return configurations.containsKey(datasourceName);
    }
    
    /**
     * Clear all cached configurations.
     * 
     * <p>After calling this method, all configurations will be re-parsed
     * from System properties on next access. Primarily useful for testing.
     */
    public void clear() {
        configurations.clear();
    }
    
    /**
     * Get the number of cached configurations.
     *
     * @return the count of cached configurations
     */
    public int size() {
        return configurations.size();
    }
}

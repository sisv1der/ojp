package org.openjproxy.grpc.server.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main cache implementation for query results using Caffeine library.
 * Thread-safe cache with TTL expiration, size limits, and table-based invalidation.
 */
public class QueryResultCache {
    private final Cache<QueryCacheKey, CachedQueryResult> cache;
    private final CacheStatistics statistics;
    private final long maxSizeBytes;
    private final AtomicLong currentSizeBytes = new AtomicLong(0);
    
    /**
     * Create a new query result cache.
     *
     * @param maxEntries Maximum number of entries in the cache
     * @param maxAge Maximum age for cached entries before expiration
     * @param maxSizeBytes Maximum total size in bytes for all cached entries
     */
    public QueryResultCache(int maxEntries, Duration maxAge, long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
        this.statistics = new CacheStatistics();
        
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(maxAge)
            .removalListener(this::onRemoval)
            .recordStats()
            .build();
    }
    
    /**
     * Retrieve a cached query result.
     *
     * @param key The cache key
     * @return The cached result, or null if not found or expired
     */
    public CachedQueryResult get(QueryCacheKey key) {
        CachedQueryResult result = cache.getIfPresent(key);
        
        if (result == null) {
            statistics.recordMiss();
            return null;
        }
        
        // Check if expired (belt and suspenders - Caffeine should handle this)
        if (result.isExpired()) {
            cache.invalidate(key);
            statistics.recordMiss();
            return null;
        }
        
        statistics.recordHit();
        return result;
    }
    
    /**
     * Store a query result in the cache.
     *
     * @param key The cache key
     * @param result The query result to cache
     */
    public void put(QueryCacheKey key, CachedQueryResult result) {
        long resultSize = result.estimateSize();
        
        // Check size limit
        while (currentSizeBytes.get() + resultSize > maxSizeBytes && cache.estimatedSize() > 0) {
            // Trigger eviction by clearing oldest entries
            cache.cleanUp();
            
            // If still too large, don't cache this result
            if (currentSizeBytes.get() + resultSize > maxSizeBytes) {
                statistics.recordRejection();
                return;
            }
        }
        
        cache.put(key, result);
        currentSizeBytes.addAndGet(resultSize);
    }
    
    /**
     * Invalidate all cached queries that depend on the specified tables.
     *
     * @param datasourceName The datasource name to filter by
     * @param tables The set of table names that were modified
     */
    public void invalidate(String datasourceName, Set<String> tables) {
        if (tables.isEmpty()) {
            return;
        }
        
        // Scan cache for entries that depend on these tables
        cache.asMap().entrySet().removeIf(entry -> {
            QueryCacheKey key = entry.getKey();
            CachedQueryResult value = entry.getValue();
            
            // Check if key belongs to this datasource
            if (!key.getDatasourceName().equals(datasourceName)) {
                return false;
            }
            
            // Check if result depends on any of the affected tables
            for (String table : tables) {
                if (value.getAffectedTables().contains(table.toLowerCase())) {
                    statistics.recordInvalidation();
                    return true;
                }
            }
            
            return false;
        });
    }
    
    /**
     * Invalidate all entries in the cache.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        currentSizeBytes.set(0);
    }
    
    /**
     * Invalidate all entries for a specific datasource.
     *
     * @param datasourceName The datasource name
     */
    public void invalidateDatasource(String datasourceName) {
        cache.asMap().entrySet().removeIf(entry -> 
            entry.getKey().getDatasourceName().equals(datasourceName)
        );
    }
    
    /**
     * Called when an entry is removed from the cache.
     */
    private void onRemoval(QueryCacheKey key, CachedQueryResult value, RemovalCause cause) {
        if (value != null) {
            currentSizeBytes.addAndGet(-value.estimateSize());
        }
        
        if (cause == RemovalCause.SIZE || cause == RemovalCause.EXPIRED) {
            statistics.recordEviction();
        }
    }
    
    /**
     * Get cache statistics.
     *
     * @return Current cache statistics
     */
    public CacheStatistics getStatistics() {
        return statistics;
    }
    
    /**
     * Get current size in bytes of all cached entries.
     *
     * @return Current size in bytes
     */
    public long getCurrentSizeBytes() {
        return currentSizeBytes.get();
    }
    
    /**
     * Get estimated number of entries in the cache.
     *
     * @return Entry count
     */
    public long getEntryCount() {
        return cache.estimatedSize();
    }
    
    /**
     * Force cleanup of expired entries.
     */
    public void cleanUp() {
        cache.cleanUp();
    }
}

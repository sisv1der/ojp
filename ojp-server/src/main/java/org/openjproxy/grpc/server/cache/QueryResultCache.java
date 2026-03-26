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
    private final QueryCacheMetrics metrics;
    private final String datasourceName;
    private final long maxSizeBytes;
    private final AtomicLong currentSizeBytes = new AtomicLong(0);
    
    /**
     * Create a new query result cache.
     *
     * @param datasourceName The datasource name for this cache
     * @param maxEntries Maximum number of entries in the cache
     * @param maxAge Maximum age for cached entries before expiration
     * @param maxSizeBytes Maximum total size in bytes for all cached entries
     * @param metrics The metrics collector (use NoOpQueryCacheMetrics if metrics disabled)
     */
    public QueryResultCache(String datasourceName, int maxEntries, Duration maxAge, long maxSizeBytes, QueryCacheMetrics metrics) {
        this.datasourceName = datasourceName;
        this.maxSizeBytes = maxSizeBytes;
        this.statistics = new CacheStatistics();
        this.metrics = metrics != null ? metrics : NoOpQueryCacheMetrics.getInstance();
        
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(maxAge)
            .removalListener(this::onRemoval)
            .recordStats()
            .build();
    }
    
    /**
     * Create a new query result cache without metrics.
     *
     * @param maxEntries Maximum number of entries in the cache
     * @param maxAge Maximum age for cached entries before expiration
     * @param maxSizeBytes Maximum total size in bytes for all cached entries
     * @deprecated Use constructor with datasourceName and metrics for production use
     */
    @Deprecated
    public QueryResultCache(int maxEntries, Duration maxAge, long maxSizeBytes) {
        this("unknown", maxEntries, maxAge, maxSizeBytes, NoOpQueryCacheMetrics.getInstance());
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
            metrics.recordCacheMiss(datasourceName, key.getNormalizedSql());
            updateCacheSizeMetrics();
            return null;
        }
        
        // Check if expired (belt and suspenders - Caffeine should handle this)
        if (result.isExpired()) {
            cache.invalidate(key);
            statistics.recordMiss();
            metrics.recordCacheMiss(datasourceName, key.getNormalizedSql());
            updateCacheSizeMetrics();
            return null;
        }
        
        statistics.recordHit();
        metrics.recordCacheHit(datasourceName, key.getNormalizedSql());
        updateCacheSizeMetrics();
        return result;
    }
    
    /**
     * Store a query result in the cache.
     *
     * @param key The cache key
     * @param result The query result to cache
     */
    public void put(QueryCacheKey key, CachedQueryResult result) {
        long resultSize = result.getEstimatedSizeBytes();
        
        System.out.println("[PUT] Starting - key=" + key.getNormalizedSql().substring(0, Math.min(30, key.getNormalizedSql().length())) + 
                          ", resultSize=" + resultSize + ", currentSize=" + currentSizeBytes.get() + ", cacheEntries=" + cache.estimatedSize());
        
        // Check if the single result itself exceeds the max size
        if (resultSize > maxSizeBytes) {
            System.out.println("[PUT] REJECT - result too large: " + resultSize + " > " + maxSizeBytes);
            statistics.recordRejection();
            metrics.recordCacheRejection(datasourceName, resultSize);
            updateCacheSizeMetrics();
            return;
        }
        
        // Check size limit
        while (currentSizeBytes.get() + resultSize > maxSizeBytes && cache.estimatedSize() > 0) {
            System.out.println("[PUT] Evicting - need room: current=" + currentSizeBytes.get() + " + result=" + resultSize + " > max=" + maxSizeBytes);
            // Trigger eviction by clearing oldest entries
            cache.cleanUp();
            
            // If still too large, don't cache this result
            if (currentSizeBytes.get() + resultSize > maxSizeBytes) {
                System.out.println("[PUT] REJECT after eviction - still too large");
                statistics.recordRejection();
                metrics.recordCacheRejection(datasourceName, resultSize);
                updateCacheSizeMetrics();
                return;
            }
        }
        
        System.out.println("[PUT] Before cache.put() - currentSize=" + currentSizeBytes.get());
        
        // cache.put() will trigger the removal listener for any replaced entry
        cache.put(key, result);
        
        System.out.println("[PUT] After cache.put(), before cleanUp() - currentSize=" + currentSizeBytes.get() + ", cacheEntries=" + cache.estimatedSize());
        
        // Ensure any pending removals are processed synchronously BEFORE adding new size
        cache.cleanUp();
        
        System.out.println("[PUT] After cleanUp(), before addAndGet() - currentSize=" + currentSizeBytes.get());
        
        // Add the new entry size (removal listener already handled old entry if replaced)
        currentSizeBytes.addAndGet(resultSize);
        
        System.out.println("[PUT] Complete - final currentSize=" + currentSizeBytes.get());
        
        updateCacheSizeMetrics();
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
                    metrics.recordCacheInvalidation(this.datasourceName, table);
                    return true;
                }
            }
            
            return false;
        });
        
        cache.cleanUp(); // Ensure removal listeners are called synchronously
        updateCacheSizeMetrics();
    }
    
    /**
     * Invalidate all entries in the cache.
     * The removal listeners will automatically update currentSizeBytes to 0.
     */
    public void invalidateAll() {
        System.out.println("[INVALIDATE_ALL] Starting - currentSize=" + currentSizeBytes.get() + ", cacheEntries=" + cache.estimatedSize());
        cache.invalidateAll();
        System.out.println("[INVALIDATE_ALL] After invalidateAll(), before cleanUp() - currentSize=" + currentSizeBytes.get() + ", cacheEntries=" + cache.estimatedSize());
        cache.cleanUp(); // Ensure removal listeners are called synchronously
        System.out.println("[INVALIDATE_ALL] After cleanUp() - currentSize=" + currentSizeBytes.get() + ", cacheEntries=" + cache.estimatedSize());
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
        cache.cleanUp(); // Ensure removal listeners are called synchronously
    }
    
    /**
     * Called when an entry is removed from the cache.
     */
    private void onRemoval(QueryCacheKey key, CachedQueryResult value, RemovalCause cause) {
        System.out.println("[ON_REMOVAL] Called - key=" + (key != null ? key.getNormalizedSql().substring(0, Math.min(30, key.getNormalizedSql().length())) : "null") + 
                          ", valueSize=" + (value != null ? value.getEstimatedSizeBytes() : 0) + 
                          ", cause=" + cause + ", currentSize=" + currentSizeBytes.get());
        
        if (value != null) {
            long sizeToRemove = value.getEstimatedSizeBytes();
            long newSize = currentSizeBytes.addAndGet(-sizeToRemove);
            System.out.println("[ON_REMOVAL] After decrement - removed=" + sizeToRemove + ", newCurrentSize=" + newSize);
        }
        
        if (cause == RemovalCause.SIZE || cause == RemovalCause.EXPIRED) {
            statistics.recordEviction();
            String reason = cause == RemovalCause.SIZE ? "size" : "ttl";
            metrics.recordCacheEviction(datasourceName, reason);
        }
        
        updateCacheSizeMetrics();
    }
    
    /**
     * Update cache size metrics.
     */
    private void updateCacheSizeMetrics() {
        metrics.updateCacheSize(datasourceName, getEntryCount(), getCurrentSizeBytes());
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

package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;

import java.util.List;

/**
 * Helper class for cache operations in query execution.
 * Centralizes cache lookup, storage, and invalidation logic.
 */
@Slf4j
public final class QueryCacheHelper {
    
    private QueryCacheHelper() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Retrieves cache configuration from a session.
     *
     * @param actionContext the action context
     * @param sessionInfo the session info
     * @return cache configuration, or null if not available
     */
    public static CacheConfiguration getCacheConfiguration(ActionContext actionContext, SessionInfo sessionInfo) {
        SessionManager sessionManager = actionContext.getSessionManager();
        if (sessionManager == null) {
            return null;
        }
        
        Session actualSession = sessionManager.getSession(sessionInfo);
        return actualSession != null ? actualSession.getCacheConfiguration() : null;
    }
    
    /**
     * Checks if caching is enabled for the given session.
     *
     * @param actionContext the action context
     * @param sessionInfo the session info
     * @return true if caching is enabled
     */
    public static boolean isCacheEnabled(ActionContext actionContext, SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            return false;
        }
        
        CacheConfiguration cacheConfig = getCacheConfiguration(actionContext, sessionInfo);
        return cacheConfig != null && cacheConfig.isEnabled();
    }
    
    /**
     * Attempts to retrieve a cached query result proto.
     *
     * @param cacheConfig cache configuration
     * @param sql SQL query
     * @param params query parameters
     * @param datasourceName datasource name
     * @return cached result proto, or null if not found or caching disabled
     */
    public static OpQueryResultProto getCachedResult(
            CacheConfiguration cacheConfig,
            String sql,
            List<Parameter> params,
            String datasourceName) {
        
        if (cacheConfig == null || !cacheConfig.isEnabled()) {
            return null;
        }
        
        // Check if query matches any cache rule
        CacheRule matchedRule = cacheConfig.getQueryRules().stream()
                .filter(rule -> rule.isEnabled() && rule.getSqlPattern().matcher(sql).matches())
                .findFirst()
                .orElse(null);
        
        if (matchedRule == null) {
            return null;
        }
        
        // Extract parameter values for cache key
        List<Object> cacheParams = extractParameterValues(params);
        
        // Build cache key
        QueryCacheKey cacheKey = new QueryCacheKey(datasourceName, sql, cacheParams);
        
        // Look up in cache
        QueryResultCacheRegistry registry = QueryResultCacheRegistry.getInstance();
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        CachedQueryResult cachedResult = cache.get(cacheKey);
        
        if (cachedResult != null) {
            log.debug("Cache HIT: datasource={}, sql={}", datasourceName, sql);
            return cachedResult.getQueryResultProto();
        }
        
        log.debug("Cache MISS: datasource={}, sql={}", datasourceName, sql);
        return null;
    }
    
    /**
     * Wraps a stream observer to enable cache storage on query completion.
     *
     * @param responseObserver the original response observer
     * @param cacheConfig cache configuration
     * @param sql SQL query
     * @param params query parameters
     * @param datasourceName datasource name
     * @return wrapped observer with caching capability, or original if caching not applicable
     */
    public static StreamObserver<OpResult> wrapWithCaching(
            StreamObserver<OpResult> responseObserver,
            CacheConfiguration cacheConfig,
            String sql,
            List<Parameter> params,
            String datasourceName) {
        
        if (cacheConfig == null || !cacheConfig.isEnabled()) {
            return responseObserver;
        }
        
        // Check if query matches any cache rule
        CacheRule matchedRule = cacheConfig.getQueryRules().stream()
                .filter(rule -> rule.isEnabled() && rule.getSqlPattern().matcher(sql).matches())
                .findFirst()
                .orElse(null);
        
        if (matchedRule == null) {
            return responseObserver;
        }
        
        // Extract parameter values for cache key
        List<Object> cacheParams = extractParameterValues(params);
        
        // Build cache key
        QueryCacheKey cacheKey = new QueryCacheKey(datasourceName, sql, cacheParams);
        
        // Wrap with caching observer
        return new CachingStreamObserver(responseObserver, cacheKey, matchedRule, datasourceName);
    }
    
    /**
     * Invalidates cache entries affected by a SQL write operation.
     *
     * @param actionContext the action context
     * @param sessionInfo the session info
     * @param sql the SQL statement that modified data
     */
    public static void invalidateCacheIfEnabled(ActionContext actionContext, SessionInfo sessionInfo, String sql) {
        if (!isCacheEnabled(actionContext, sessionInfo)) {
            return;  // Caching not enabled
        }
        
        try {
            // Extract modified tables from SQL
            java.util.Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(sql);
            
            if (modifiedTables.isEmpty()) {
                log.debug("No tables extracted from SQL, skipping cache invalidation");
                return;
            }
            
            // Get datasource and cache
            String datasourceName = sessionInfo.getConnHash();
            QueryResultCache cache = QueryResultCacheRegistry.getInstance().get(datasourceName);
            
            if (cache == null) {
                log.debug("No cache found for datasource: {}", datasourceName);
                return;
            }
            
            // Invalidate cache entries for affected tables
            for (String table : modifiedTables) {
                cache.invalidate(table);
                log.debug("Cache invalidated: datasource={}, table={}", datasourceName, table);
            }
        } catch (Exception e) {
            // Log but don't fail the query - cache invalidation is best-effort
            log.warn("Failed to invalidate cache: error={}", e.getMessage());
        }
    }
    
    /**
     * Extracts parameter values from Parameter list.
     *
     * @param params parameter list
     * @return list of parameter values
     */
    private static List<Object> extractParameterValues(List<Parameter> params) {
        if (params == null) {
            return List.of();
        }
        
        return params.stream()
                .flatMap(p -> p.getValues() != null ? 
                        p.getValues().stream() : 
                        java.util.stream.Stream.empty())
                .toList();
    }
}

package org.openjproxy.grpc.server.cache;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;

import java.util.List;

/**
 * Helper class for cache operations in query execution.
 * Centralizes cache lookup and storage logic.
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
     * Attempts to retrieve a cached query result.
     *
     * @param cacheConfig cache configuration
     * @param sql SQL query
     * @param params query parameters
     * @param datasourceName datasource name
     * @return cached result, or null if not found or caching disabled
     */
    public static OpQueryResult getCachedResult(
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
            return new OpQueryResult(
                    cachedResult.getColumns(),
                    null, // resultSetUUID - not needed for cached results
                    cachedResult.getRows());
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

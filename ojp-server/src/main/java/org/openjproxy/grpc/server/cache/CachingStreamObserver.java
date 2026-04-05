package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpResult;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * StreamObserver wrapper that intercepts query results for caching.
 * <p>
 * Forwards all calls to the wrapped observer immediately (no delay for client),
 * and stores the first OpResult in cache after successful query completion.
 * </p>
 */
@Slf4j
public class CachingStreamObserver implements StreamObserver<OpResult> {
    private final StreamObserver<OpResult> delegate;
    private final QueryCacheKey cacheKey;
    private final CacheRule cacheRule;
    private final String datasourceName;
    private OpResult capturedResult = null;
    private static final long MAX_CACHE_SIZE_BYTES = 200L * 1024; // 200KB - cast to long
    
    public CachingStreamObserver(
            StreamObserver<OpResult> delegate,
            QueryCacheKey cacheKey,
            CacheRule cacheRule,
            String datasourceName) {
        this.delegate = delegate;
        this.cacheKey = cacheKey;
        this.cacheRule = cacheRule;
        this.datasourceName = datasourceName;
    }
    
    @Override
    public void onNext(OpResult value) {
        // Forward immediately to client (no delay)
        delegate.onNext(value);
        
        // Capture first result for caching (if not already captured)
        if (capturedResult == null && value.hasQueryResult()) {
            capturedResult = value;
        }
    }
    
    @Override
    public void onError(Throwable t) {
        // Query failed - don't cache, forward error
        delegate.onError(t);
    }
    
    @Override
    public void onCompleted() {
        // Forward completion to client first
        delegate.onCompleted();
        
        // Then attempt to cache the result (if captured)
        if (capturedResult != null) {
            try {
                storeToCacheIfEligible();
            } catch (Exception e) {
                // Log but don't fail - caching is best-effort
                log.warn("Failed to store result in cache: datasource={}, error={}", 
                        datasourceName, e.getMessage());
            }
        }
    }
    
    private void storeToCacheIfEligible() {
        com.openjproxy.grpc.OpQueryResultProto queryResult = capturedResult.getQueryResult();
        
        // Check if result is empty
        if (queryResult.getRowsCount() == 0) {
            log.debug("Empty result set, not caching: datasource={}", datasourceName);
            return;
        }
        
        // Extract column names (labels)
        List<String> columnNames = new ArrayList<>(queryResult.getLabelsList());
        
        // Extract rows
        List<List<Object>> rows = new ArrayList<>();
        for (com.openjproxy.grpc.ResultRow row : queryResult.getRowsList()) {
            List<Object> rowValues = new ArrayList<>();
            for (com.openjproxy.grpc.ParameterValue val : row.getColumnsList()) {
                rowValues.add(convertProtoValueToObject(val));
            }
            rows.add(rowValues);
        }
        
        // Estimate size
        long estimatedSize = estimateResultSize(rows, columnNames);
        if (estimatedSize > MAX_CACHE_SIZE_BYTES) {
            log.debug("Result too large to cache: size={}KB, max={}KB, datasource={}", 
                    estimatedSize / 1024, MAX_CACHE_SIZE_BYTES / 1024, datasourceName);
            return;
        }
        
        // Build CachedQueryResult
        Instant now = Instant.now();
        Duration ttl = cacheRule.getTtl();
        Set<String> affectedTables = new HashSet<>(cacheRule.getInvalidateOn());
        
        // Column types - use VARCHAR as generic type for all columns
        // OpQueryResultProto only has labels (column names), not type info
        List<String> columnTypes = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            columnTypes.add("VARCHAR");  // Generic type
        }
        
        CachedQueryResult cachedResult = new CachedQueryResult(
                rows, columnNames, columnTypes, now, now.plus(ttl), affectedTables);
        
        // Store in cache
        QueryResultCacheRegistry registry = QueryResultCacheRegistry.getInstance();
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        cache.put(cacheKey, cachedResult);
        
        log.debug("Stored in cache: datasource={}, rows={}, size={}KB", 
                datasourceName, rows.size(), estimatedSize / 1024);
    }
    
    private Object convertProtoValueToObject(com.openjproxy.grpc.ParameterValue val) {
        if (val.hasStringValue()) return val.getStringValue();
        if (val.hasIntValue()) return val.getIntValue();
        if (val.hasLongValue()) return val.getLongValue();
        if (val.hasDoubleValue()) return val.getDoubleValue();
        if (val.hasBoolValue()) return val.getBoolValue();
        if (val.hasBytesValue()) return val.getBytesValue().toByteArray();
        if (val.hasIsNull()) return null;
        return null;
    }
    
    private long estimateResultSize(List<List<Object>> rows, List<String> columnNames) {
        long size = columnNames.size() * 50L; // Column names overhead
        for (List<Object> row : rows) {
            for (Object val : row) {
                if (val == null) {
                    size += 8;
                } else if (val instanceof String str) {
                    size += str.length() * 2L;
                } else if (val instanceof byte[] bytes) {
                    size += bytes.length;
                } else {
                    size += 16; // Primitive or small object
                }
            }
        }
        return size;
    }
}

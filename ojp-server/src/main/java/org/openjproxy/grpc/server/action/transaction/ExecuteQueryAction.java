package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.statement.StatementFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;
import static org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper.executeWithResilience;

@Slf4j
public class ExecuteQueryAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteQueryAction INSTANCE = new ExecuteQueryAction();

    /**
     * Private constructor for singleton.
     */
    private ExecuteQueryAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static ExecuteQueryAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());

        executeWithResilience(context, request, responseObserver, () ->
                        executeQueryInternal(context, request, responseObserver),
                null, "query");
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(ActionContext actionContext, StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {

        ConnectionSessionDTO dto = sessionConnection(actionContext, request.getSession(), true);

        // Phase 6: Cache Lookup (before query execution)
        String sql = request.getSql();
        org.openjproxy.grpc.server.cache.CacheConfiguration cacheConfig = 
                dto.getSession().getCacheConfiguration();
        
        if (cacheConfig != null && cacheConfig.isEnabled()) {
            // Check if this query matches any cache rules
            org.openjproxy.grpc.server.cache.CacheRule matchedRule = cacheConfig.findMatchingRule(sql);
            
            if (matchedRule != null && matchedRule.isEnabled()) {
                // Extract parameters for cache key
                List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
                List<Object> cacheParams = params != null ? 
                        params.stream().map(Parameter::getValue).toList() : List.of();
                
                // Build cache key (datasource from connection hash)
                String datasourceName = dto.getSession().getConnHash();
                org.openjproxy.grpc.server.cache.QueryCacheKey cacheKey = 
                        new org.openjproxy.grpc.server.cache.QueryCacheKey(
                                datasourceName, sql, cacheParams);
                
                // Try to get from cache
                org.openjproxy.grpc.server.cache.QueryResultCacheRegistry cacheRegistry = 
                        org.openjproxy.grpc.server.cache.QueryResultCacheRegistry.getInstance();
                org.openjproxy.grpc.server.cache.QueryResultCache cache = 
                        cacheRegistry.getOrCreate(datasourceName);
                
                org.openjproxy.grpc.server.cache.CachedQueryResult cachedResult = cache.get(cacheKey);
                
                if (cachedResult != null && !cachedResult.isExpired()) {
                    // CACHE HIT - Return cached result
                    log.debug("Cache HIT: datasource={}, sql={}", datasourceName, 
                            sql.substring(0, Math.min(sql.length(), 50)));
                    
                    OpResult result = org.openjproxy.grpc.server.cache.CachedResultHandler
                            .convertToOpResult(cachedResult);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                    return;  // Skip database execution
                } else {
                    // CACHE MISS - Continue to database execution
                    log.debug("Cache MISS: datasource={}, sql={}", datasourceName,
                            sql.substring(0, Math.min(sql.length(), 50)));
                }
            }
        }

        // Phase 2: SQL Enhancement with timing
        long enhancementStartTime = System.currentTimeMillis();

        var sqlEnhancerEngine = actionContext.getSqlEnhancerEngine();
        var datasourceMap = actionContext.getDatasourceMap();
        var sessionManager = actionContext.getSessionManager();

        if (sqlEnhancerEngine.isEnabled()) {
            // Ensure schema is loaded before enhancement (on-demand, only once)
            try {
                // Get the DataSource for this connection
                String dsKey = dto.getSession().getConnHash();
                DataSource dataSource = datasourceMap.get(dsKey);

                if (dataSource != null) {
                    // Get catalog and schema from the connection
                    Connection connection = dto.getConnection();
                    String catalogName = connection.getCatalog();
                    String schemaName = connection.getSchema();

                    // PostgreSQL: Use "public" schema if schema name is null or empty
                    // This ensures tables created in the default schema are visible to Calcite
                    if ((schemaName == null || schemaName.isEmpty()) &&
                            connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                        schemaName = "public";
                        log.debug("Using default PostgreSQL 'public' schema for schema loading");
                    }

                    // Ensure schema is loaded (thread-safe, idempotent)
                    sqlEnhancerEngine.ensureSchemaLoaded(dataSource, catalogName, schemaName);
                } else {
                    log.debug("No DataSource found for connection hash: {}", dsKey);
                }
            } catch (Exception e) {
                // Log but don't fail - enhancement can proceed without schema
                log.warn("Failed to ensure schema loaded: {}", e.getMessage());
            }

            org.openjproxy.grpc.server.sql.SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
            sql = result.getEnhancedSql();

            long enhancementDuration = System.currentTimeMillis() - enhancementStartTime;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        
        // Phase 7: Wrap response observer for cache storage (if caching enabled)
        StreamObserver<OpResult> finalObserver = responseObserver;
        org.openjproxy.grpc.server.cache.CacheRule matchedCacheRule = null;
        org.openjproxy.grpc.server.cache.QueryCacheKey finalCacheKey = null;
        String finalDatasourceName = null;
        
        if (cacheConfig != null && cacheConfig.isEnabled()) {
            matchedCacheRule = cacheConfig.findMatchingRule(sql);
            if (matchedCacheRule != null && matchedCacheRule.isEnabled()) {
                // Prepare for caching after query execution
                List<Parameter> cacheParams = params != null ? params : List.of();
                List<Object> cacheParamValues = cacheParams.stream()
                        .map(Parameter::getValue)
                        .toList();
                finalDatasourceName = dto.getSession().getConnHash();
                finalCacheKey = new org.openjproxy.grpc.server.cache.QueryCacheKey(
                        finalDatasourceName, sql, cacheParamValues);
                
                // Wrap observer to capture results for caching
                finalObserver = new CachingStreamObserver(
                        responseObserver,
                        finalCacheKey,
                        matchedCacheRule,
                        finalDatasourceName);
            }
        }
        
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request);
            String resultSetUUID = sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, finalObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, finalObserver);
        }
    }
    
    /**
     * StreamObserver wrapper that intercepts query results for caching.
     * <p>
     * Forwards all calls to the wrapped observer immediately (no delay for client),
     * and stores the first OpResult in cache after successful query completion.
     * </p>
     */
    private static class CachingStreamObserver implements StreamObserver<OpResult> {
        private final StreamObserver<OpResult> delegate;
        private final org.openjproxy.grpc.server.cache.QueryCacheKey cacheKey;
        private final org.openjproxy.grpc.server.cache.CacheRule cacheRule;
        private final String datasourceName;
        private OpResult capturedResult = null;
        private static final long MAX_CACHE_SIZE_BYTES = 200 * 1024; // 200KB
        
        public CachingStreamObserver(
                StreamObserver<OpResult> delegate,
                org.openjproxy.grpc.server.cache.QueryCacheKey cacheKey,
                org.openjproxy.grpc.server.cache.CacheRule cacheRule,
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
            com.openjproxy.grpc.QueryResult queryResult = capturedResult.getQueryResult();
            
            // Check if result is empty
            if (queryResult.getRowsCount() == 0) {
                log.debug("Empty result set, not caching: datasource={}", datasourceName);
                return;
            }
            
            // Extract column names
            List<String> columnNames = new ArrayList<>();
            for (com.openjproxy.grpc.ColumnMetadata col : queryResult.getColumnsList()) {
                columnNames.add(col.getName());
            }
            
            // Extract rows
            List<List<Object>> rows = new ArrayList<>();
            for (com.openjproxy.grpc.Row row : queryResult.getRowsList()) {
                List<Object> rowValues = new ArrayList<>();
                for (com.openjproxy.grpc.Value val : row.getValuesList()) {
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
            java.time.Instant now = java.time.Instant.now();
            java.time.Duration ttl = cacheRule.getTtl();
            java.util.Set<String> affectedTables = new java.util.HashSet<>(cacheRule.getInvalidateOn());
            
            // Column types - extract from metadata or use generic
            List<String> columnTypes = new ArrayList<>();
            for (com.openjproxy.grpc.ColumnMetadata col : queryResult.getColumnsList()) {
                columnTypes.add(col.getType() != null ? col.getType() : "VARCHAR");
            }
            
            org.openjproxy.grpc.server.cache.CachedQueryResult cachedResult = 
                    new org.openjproxy.grpc.server.cache.CachedQueryResult(
                            rows, columnNames, columnTypes, now, now.plus(ttl), affectedTables);
            
            // Store in cache
            org.openjproxy.grpc.server.cache.QueryResultCacheRegistry registry = 
                    org.openjproxy.grpc.server.cache.QueryResultCacheRegistry.getInstance();
            org.openjproxy.grpc.server.cache.QueryResultCache cache = 
                    registry.getOrCreate(datasourceName);
            
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
}

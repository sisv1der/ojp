package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.transaction.CommitTransactionAction;
import org.openjproxy.grpc.server.action.transaction.RollbackTransactionAction;
import org.openjproxy.grpc.server.action.transaction.StartTransactionAction;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    private final SessionManager sessionManager;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();
    
    // Per-datasource cache configurations (shared with SessionManager)
    private final Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap;

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

  
    // SQL statement metrics (only used by executeQuery/executeUpdate)
    private final org.openjproxy.grpc.server.metrics.SqlStatementMetrics sqlStatementMetrics;
  
    public StatementServiceImpl(SessionManager sessionManager, CircuitBreakerRegistry circuitBreakerRegistry,
            ServerConfiguration serverConfiguration,
            Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap) {
        this.sessionManager = sessionManager;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.cacheConfigurationMap = cacheConfigurationMap != null ? cacheConfigurationMap : new ConcurrentHashMap<>();
        
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        initializeXAPoolProvider();

        // Create SQL statement metrics from the registered OpenTelemetry instance (if available)
        this.sqlStatementMetrics = createSqlStatementMetrics();

        // Initialize ActionContext with all shared state
        // Map for storing XADataSources (native database XADataSource, not Atomikos)
        Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
        // XA Transaction Registries (one per connection hash for isolated transaction
        // management)
        Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
        // Unpooled connection details map (for passthrough mode when pooling is
        // disabled)
        Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();
        // Cluster health tracker for monitoring health changes
        ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();
        this.actionContext = new org.openjproxy.grpc.server.action.ActionContext(
                datasourceMap,
                xaDataSourceMap,
                xaRegistries,
                unpooledConnectionDetailsMap,
                dbNameMap,
                slowQuerySegregationManagers,
                this.cacheConfigurationMap,
                xaPoolProvider,
                xaCoordinator,
                clusterHealthTracker,
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                sqlStatementMetrics);
    }

    /**
     * Creates SQL statement metrics using the registered OpenTelemetry instance.
     * Falls back to no-op metrics when OpenTelemetry is unavailable.
     */
    private org.openjproxy.grpc.server.metrics.SqlStatementMetrics createSqlStatementMetrics() {
        io.opentelemetry.api.OpenTelemetry openTelemetry =
                org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder.getInstance();
        if (openTelemetry != null) {
            log.info("OpenTelemetry instance available – enabling SQL statement metrics");
            return new org.openjproxy.grpc.server.metrics.OpenTelemetrySqlStatementMetrics(openTelemetry);
        }
        log.info("OpenTelemetry not available – SQL statement metrics disabled (no-op)");
        return org.openjproxy.grpc.server.metrics.NoOpSqlStatementMetrics.INSTANCE;
    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * This should be called at the beginning of any method that operates on a session.
     *
     * @param sessionInfo the session information
     */
    private void updateSessionActivity(SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            sessionManager.updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Initialize XA Pool Provider if XA pooling is enabled in configuration.
     * Loads the provider via ServiceLoader (Commons Pool 2 by default).
     */
    private void initializeXAPoolProvider() {
        // XA pooling is always enabled
        // Select the provider with the HIGHEST priority (100 = highest, 0 = lowest)

        try {
            ServiceLoader<XAConnectionPoolProvider> loader = ServiceLoader.load(XAConnectionPoolProvider.class);
            XAConnectionPoolProvider selectedProvider = null;
            int highestPriority = Integer.MIN_VALUE;

            for (XAConnectionPoolProvider provider : loader) {
                if (provider.isAvailable()) {
                    log.debug("Found available XA Pool Provider: {} (priority: {})",
                            provider.getClass().getName(), provider.getPriority());

                    if (provider.getPriority() > highestPriority) {
                        selectedProvider = provider;
                        highestPriority = provider.getPriority();
                    }
                }
            }

            if (selectedProvider != null) {
                this.xaPoolProvider = selectedProvider;
                log.info("Selected XA Pool Provider: {} (priority: {})",
                        selectedProvider.getClass().getName(), selectedProvider.getPriority());

                // Update ActionContext with initialized provider (if actionContext is already
                // created)
                if (this.actionContext != null) {
                    this.actionContext.setXaPoolProvider(selectedProvider);
                }
            } else {
                log.warn("No available XA Pool Provider found via ServiceLoader, XA pooling will be unavailable");
            }
        } catch (Exception e) {
            log.error("Failed to load XA Pool Provider: {}", e.getMessage(), e);
        }
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        org.openjproxy.grpc.server.action.connection.ConnectAction.getInstance()
                .execute(actionContext, connectionDetails, responseObserver);
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.ExecuteUpdateAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());

        executeWithResilience(request, responseObserver, () ->
            executeQueryInternal(request, responseObserver),
                null, "query");
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver)
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
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, finalObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
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
        
        private Object convertProtoValueToObject(com.openjproxy.grpc.Value val) {
            if (val.hasStringVal()) return val.getStringVal();
            if (val.hasIntVal()) return val.getIntVal();
            if (val.hasLongVal()) return val.getLongVal();
            if (val.hasDoubleVal()) return val.getDoubleVal();
            if (val.hasBoolVal()) return val.getBoolVal();
            if (val.hasBytesVal()) return val.getBytesVal().toByteArray();
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

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.FetchNextRowsAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        return org.openjproxy.grpc.server.action.streaming.CreateLobAction.getInstance()
                .execute(actionContext, responseObserver);
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        org.openjproxy.grpc.server.action.streaming.ReadLobAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Builder
    public static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        TerminateSessionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        StartTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        CommitTransactionAction.getInstance().execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        RollbackTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        CallResourceAction.getInstance().execute(actionContext, request, responseObserver);
    }

    /**
     * Helper method to centralize session validation, activity updates, cluster health processing,
     * circuit breaker checks, and slow query segregation for statement execution.
     * This resolves SonarQube duplication issues.
     */
    private void executeWithResilience(StatementRequest request, StreamObserver<OpResult> responseObserver,
                                       StatementExecution executionLogic, SqlErrorType sqlDataExceptionType, String operationName) {

        // Ensure session isn't null
        if (request.getSession() == null || StringUtils.isBlank(request.getSession().getConnHash())) {
            sendSQLExceptionMetadata(new SQLException("Invalid request: Session or ConnHash is missing"), responseObserver);
            log.error("Invalid {} request: Session or ConnHash is missing", operationName);
            return;
        }

        // Update session activity
        updateSessionActivity(request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(actionContext, request.getSession());


        String connHash = request.getSession().getConnHash();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(connHash);

        // Get the appropriate slow query segregation manager for this datasource
        SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);
        long sqlStartMs = System.currentTimeMillis();
        try {
            circuitBreaker.preCheck(stmtHash);

            // Execute with slow query segregation, passing actual SQL for metric labelling
            manager.executeWithSegregation(stmtHash, request.getSql(), () -> {
                executionLogic.execute();
                return null;
            });

            circuitBreaker.onSuccess(stmtHash);

        } catch(SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            SqlErrorType type = sqlDataExceptionType != null
                    ? sqlDataExceptionType
                    : SqlErrorType.SQL_EXCEPTION;

            sendSQLExceptionMetadata(e, responseObserver, type);

        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        } finally {
            // Record SQL execution time for all connections (XA and non-XA) regardless of
            // manager state. This is the single authoritative place for SQL metrics.
            String sql = request.getSql();
            if (sql != null && !sql.isEmpty()) {
                long executionTimeMs = System.currentTimeMillis() - sqlStartMs;
                sqlStatementMetrics.recordSqlExecution(
                        sql, executionTimeMs, manager.isSlowOperation(stmtHash));
            }
        }
    }

    // ===== XA Transaction Operations =====

    @Override
    public void xaStart(com.openjproxy.grpc.XaStartRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaStartAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaEnd(com.openjproxy.grpc.XaEndRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaEndAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaPrepare(com.openjproxy.grpc.XaPrepareRequest request,
            StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        XaPrepareAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaCommit(com.openjproxy.grpc.XaCommitRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaCommitAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRollback(com.openjproxy.grpc.XaRollbackRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaRollbackAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRecover(com.openjproxy.grpc.XaRecoverRequest request,
            StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        XaRecoverAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaForget(com.openjproxy.grpc.XaForgetRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaForgetAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaSetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaSetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaGetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaGetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);

    }

    @Override
    public void xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request,
            StreamObserver<com.openjproxy.grpc.XaIsSameRMResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaIsSameRMAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    /**
     * Shuts down the SQL enhancer engine and releases associated resources.
     * This method should be called during server shutdown to ensure proper cleanup.
     */
    public void shutdown() {
        if (sqlEnhancerEngine != null) {
            log.info("Shutting down SQL enhancer engine");
            sqlEnhancerEngine.shutdown();
        }
    }

    @FunctionalInterface
    private interface StatementExecution {
        void execute() throws Exception;
    }
}

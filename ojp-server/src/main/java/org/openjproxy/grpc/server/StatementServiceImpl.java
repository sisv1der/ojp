package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.ResultType;
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
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.action.transaction.StartTransactionAction;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.openjproxy.grpc.server.action.transaction.RollbackTransactionAction;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.transaction.CommitTransactionAction;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreaker circuitBreaker,
            ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        initializeXAPoolProvider();

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
                xaPoolProvider,
                xaCoordinator,
                clusterHealthTracker,
                sessionManager,
                circuitBreaker,
                serverConfiguration);
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
            // Create a disabled manager as fallback
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());
        
        // Update session activity
        updateSessionActivity(request.getSession());
        
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(actionContext, request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () -> executeUpdateInternal(request));

            responseObserver.onNext(result);
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);

        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Internal method for executing updates without segregation logic.
     */
    private OpResult executeUpdateInternal(StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo;
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        try {
            // Check if SQL requires session affinity (temporary tables, session variables, etc.)
            boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());
            
            dto = sessionConnection(actionContext, request.getSession(),
                    StatementRequestValidator.isAddBatchOperation(request)
                    || StatementRequestValidator.hasAutoGeneratedKeysFlag(request)
                    || requiresSessionAffinity);
            returnSessionInfo = dto.getSession();

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID())
                            ? sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID())
                            : null;
            if (CollectionUtils.isNotEmpty(params) || ps != null) {
                if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                    Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
                    for (Object o : lobs) {
                        LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
                        Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(),
                                lobIS.getUuid());
                        Integer parameterIndex = (Integer) metadata
                                .get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX);
                        ps.setBinaryStream(parameterIndex, lobIS);
                    }
                    if (DbName.POSTGRES.equals(dto.getDbName())) {// Postgres requires check if the lob streams are
                                                                  // fully consumed.
                        sessionManager.waitLobStreamsConsumption(dto.getSession());
                    }
                    if (ps != null) {
                        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
                    }
                } else {
                    ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params,
                            request);
                    if (StatementRequestValidator.hasAutoGeneratedKeysFlag(request)) {
                        String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        opResultBuilder.setUuid(psNewUUID);
                    }
                }
                if (StatementRequestValidator.isAddBatchOperation(request)) {
                    ps.addBatch();
                    if (request.getStatementUUID().isBlank()) {
                        psUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                    } else {
                        psUUID = request.getStatementUUID();
                    }
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            if (StatementRequestValidator.isAddBatchOperation(request)) {
                return opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setUuidValue(psUUID).build();
            } else {
                return opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setIntValue(updated).build();
            }
        } finally {
            // If there is no session, close statement and connection
            if ((dto.getSession() == null || StringUtils.isEmpty(dto.getSession().getSessionUUID())) && stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        log.error("Failure closing statement: " + e.getMessage(), e);
                    }
                    try {
                        stmt.getConnection().close();
                    } catch (SQLException e) {
                        log.error("Failure closing connection: " + e.getMessage(), e);
                    }
                }

        }
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        
        // Update session activity
        updateSessionActivity(request.getSession());
        
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(actionContext, request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                executeQueryInternal(request, responseObserver);
                return null; // Void return for query execution
            });

            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        
        ConnectionSessionDTO dto = sessionConnection(actionContext, request.getSession(), true);

        // Phase 2: SQL Enhancement with timing
        String sql = request.getSql();
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
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, responseObserver);
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
     * As DB2 eagerly closes result sets in multiple situations the result set
     * metadata is saved a priori in a session
     * attribute and has to be read in a special manner treated in this method.
     *
     * @param request
     * @param responseObserver
     * @return boolean
     * @throws SQLException
     */
    @SneakyThrows
    private boolean db2SpecialResultSetMetadata(CallResourceRequest request,
            StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(this.dbNameMap.get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) this.sessionManager.getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParamsCount() > 0)
                    ? ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList())
                    : EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
            responseObserver.onNext(CallResourceResponse.newBuilder()
                    .setSession(request.getSession())
                    .addValues(ProtoConverter.toParameterValue(metadataResult))
                    .build());
            responseObserver.onCompleted();
            return true;
        }
        return false;
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
}

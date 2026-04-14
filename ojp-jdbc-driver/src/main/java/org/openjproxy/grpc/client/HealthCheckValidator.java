package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.ConnectivityState;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates server health by attempting a direct connection.
 * Used to detect when a failed server has recovered.
 */
public class HealthCheckValidator {
    
    private static final Logger log = LoggerFactory.getLogger(HealthCheckValidator.class);
    
    private final HealthCheckConfig config;
    private final MultinodeConnectionManager connectionManager;
    
    public HealthCheckValidator(HealthCheckConfig config, MultinodeConnectionManager connectionManager) {
        this.config = config;
        this.connectionManager = connectionManager;
    }
    
    /**
     * Validates if a server is healthy by attempting a connection.
     * 
     * @param endpoint The server endpoint to validate
     * @param connectionDetails Connection details to use for validation
     * @return true if server is healthy, false otherwise
     */
    public boolean validateServer(ServerEndpoint endpoint, ConnectionDetails connectionDetails) {
        if (endpoint == null) {
            return false;
        }
        
        log.debug("Validating server health: {}", endpoint.getAddress());
        
        // Check if this is a heartbeat check (empty connection details)
        // Do this BEFORE attempting connection to avoid channel errors
        if (StringUtils.isBlank(connectionDetails.getUrl()) && 
            StringUtils.isBlank(connectionDetails.getUser()) &&
            StringUtils.isBlank(connectionDetails.getPassword())) {
            log.debug("Using heartbeat health check for {}", endpoint.getAddress());
            
            try {
                // Attempt to get channel and stub for this server
                MultinodeConnectionManager.ChannelAndStub channelAndStub = 
                    connectionManager.getChannelAndStub(endpoint);
                
                if (channelAndStub == null) {
                    log.debug("No channel available for {}, attempting to create", endpoint.getAddress());
                    channelAndStub = connectionManager.createChannelAndStubForEndpoint(endpoint);
                }
                
                // Try heartbeat connection
                log.debug("Attempting heartbeat connection to {}", endpoint.getAddress());
                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
                
                log.info("Server {} heartbeat health check PASSED", endpoint.getAddress());
                return true;
                
            } catch (Exception e) {
                log.debug("Server {} heartbeat health check FAILED: {}", endpoint.getAddress(), e.getMessage());
                return false;
            }
        }
        
        // Full connection test with actual credentials
        try {
            // Attempt to get channel and stub for this server
            MultinodeConnectionManager.ChannelAndStub channelAndStub = 
                connectionManager.getChannelAndStub(endpoint);
            
            if (channelAndStub == null) {
                log.debug("No channel available for {}, attempting to create", endpoint.getAddress());
                channelAndStub = connectionManager.createChannelAndStubForEndpoint(endpoint);
            }
            
            // Try to establish a test connection
            log.debug("Attempting test connection to {}", endpoint.getAddress());
            SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

            if (sessionInfo != null && sessionInfo.getSessionUUID() != null && 
                !sessionInfo.getSessionUUID().isEmpty()) {
                
                // Connection successful
                log.info("Server {} health check PASSED", endpoint.getAddress());
                
                // Close the test session
                try {
                    connectionManager.closeSession(sessionInfo.getSessionUUID());
                } catch (Exception e) {
                    log.debug("Failed to close test session {}: {}", 
                        sessionInfo.getSessionUUID(), e.getMessage());
                }
                
                return true;
            }
            
            log.debug("Server {} health check FAILED - no valid session returned", endpoint.getAddress());
            return false;
            
        } catch (Exception e) {
            log.debug("Server {} health check FAILED: {}", endpoint.getAddress(), e.getMessage());
            return false;
        }
    }
    
    /**
     * Validates if a server is healthy using the gRPC channel connectivity state.
     * This avoids making application-level RPC calls with empty credentials that
     * the server correctly rejects, which would falsely mark it as unhealthy.
     * READY / IDLE / CONNECTING all mean the transport can reach the server.
     * Only TRANSIENT_FAILURE or SHUTDOWN indicate a genuine connectivity problem.
     *
     * @param endpoint The server endpoint to validate
     * @return true if the gRPC channel is reachable, false otherwise
     */
    public boolean validateServer(ServerEndpoint endpoint) {
        if (endpoint == null) {
            return false;
        }

        try {
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(endpoint);

            if (channelAndStub == null) {
                log.debug("No channel available for {}, attempting to create", endpoint.getAddress());
                channelAndStub = connectionManager.createChannelAndStubForEndpoint(endpoint);
            }

            if (channelAndStub == null) {
                log.debug("Server {} channel state check FAILED: could not obtain channel", endpoint.getAddress());
                return false;
            }

            // Request a connection attempt if the channel is IDLE so gRPC starts connecting.
            ConnectivityState state = channelAndStub.channel.getState(true);
            boolean healthy = state != ConnectivityState.TRANSIENT_FAILURE
                    && state != ConnectivityState.SHUTDOWN;

            if (healthy) {
                log.debug("Server {} channel state check PASSED (state={})", endpoint.getAddress(), state);
            } else {
                log.debug("Server {} channel state check FAILED (state={})", endpoint.getAddress(), state);
            }
            return healthy;

        } catch (Exception e) {
            log.debug("Server {} channel state check FAILED: {}", endpoint.getAddress(), e.getMessage());
            return false;
        }
    }
}

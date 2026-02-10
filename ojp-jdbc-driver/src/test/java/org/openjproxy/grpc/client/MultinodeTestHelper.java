package org.openjproxy.grpc.client;

import io.grpc.StatusRuntimeException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for multinode integration tests.
 * Contains common logic for exception tracking and categorization.
 */
public class MultinodeTestHelper {
    
    /**
     * Categorizes and increments failure counters based on exception type.
     * 
     * @param e The exception that caused the failure
     * @param totalFailedQueries Counter for all failures
     * @param nonConnectivityFailedQueries Counter for non-connectivity failures only
     */
    public static void incrementFailures(Exception e, 
                                        AtomicInteger totalFailedQueries,
                                        AtomicInteger nonConnectivityFailedQueries) {
        totalFailedQueries.incrementAndGet();
        
        // Check if this is a connectivity-related failure
        // 1. StatusRuntimeException with UNAVAILABLE - direct server unavailability
        // 2. Session invalidation errors - indirect result of server unavailability
        boolean isConnectivityRelated = false;
        
        if (e instanceof StatusRuntimeException && e.getMessage().contains("UNAVAILABLE")) {
            isConnectivityRelated = true;
        } else if (GrpcExceptionHandler.isSessionInvalidationError(e)) {
            // Session invalidation due to server failure - use shared detection logic
            isConnectivityRelated = true;
        }
        
        if (!isConnectivityRelated) {
            // Errors non related to the fact that a node is down
            nonConnectivityFailedQueries.incrementAndGet();
        }
    }
    
    /**
     * Determines if an exception is connectivity-related (expected during server restarts).
     * 
     * @param e The exception to check
     * @return true if the exception is connectivity-related, false otherwise
     */
    public static boolean isConnectivityRelated(Exception e) {
        if (e instanceof StatusRuntimeException && e.getMessage().contains("UNAVAILABLE")) {
            return true;
        }
        return GrpcExceptionHandler.isSessionInvalidationError(e);
    }
}

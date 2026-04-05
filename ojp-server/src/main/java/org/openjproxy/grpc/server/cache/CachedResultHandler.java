package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper class to convert cached query results into OpResult format.
 * <p>
 * Since we now cache proto objects directly, this class simply wraps
 * the cached proto into an OpResult without any conversion overhead.
 * </p>
 */
@Slf4j
public class CachedResultHandler {

    /**
     * Converts a cached query result proto into an OpResult for streaming back to the client.
     * <p>
     * Since the cache stores OpQueryResultProto directly, this is a zero-copy operation.
     * </p>
     *
     * @param cachedResult The cached result from Caffeine cache
     * @return OpResult containing the cached proto data
     */
    public static OpResult convertToOpResult(CachedQueryResult cachedResult) {
        
        if (cachedResult == null) {
            throw new IllegalArgumentException("CachedResult cannot be null");
        }

        log.debug("Converted cached result to OpResult: {} rows, {} columns (zero-copy)",
                cachedResult.getRowCount(), cachedResult.getColumnCount());

        // Return the proto directly - zero conversion overhead
        return OpResult.newBuilder()
                .setQueryResult(cachedResult.getQueryResultProto())
                .build();
    }
}

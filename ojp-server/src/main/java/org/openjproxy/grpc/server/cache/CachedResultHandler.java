package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpResult;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.OpQueryResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to convert cached query results into OpResult format.
 * <p>
 * Transforms CachedQueryResult domain objects (stored in Caffeine cache)
 * into the OpQueryResult/OpResult format expected by the gRPC response.
 * </p>
 */
@Slf4j
public class CachedResultHandler {

    /**
     * Converts a cached query result into an OpResult for streaming back to the client.
     *
     * @param cachedResult The cached result from Caffeine cache
     * @param resultSetUUID The UUID to identify this result set
     * @param isComplete Whether this is the final block of results
     * @return OpResult containing the cached data
     */
    public static OpResult convertToOpResult(
            CachedQueryResult cachedResult,
            String resultSetUUID,
            boolean isComplete) {
        
        if (cachedResult == null) {
            throw new IllegalArgumentException("CachedResult cannot be null");
        }

        // Build OpQueryResult with column names
        OpQueryResult.OpQueryResultBuilder builder = OpQueryResult.builder()
                .labels(new ArrayList<>(cachedResult.getColumnNames()))
                .resultSetUUID(resultSetUUID)
                .resultSetMode("")  // Empty for normal mode (not row-by-row)
                .resultSetCompleted(isComplete);

        // Convert rows to Object arrays
        List<Object[]> results = new ArrayList<>();
        for (List<Object> row : cachedResult.getRows()) {
            results.add(row.toArray(new Object[0]));
        }
        builder.results(results);

        OpQueryResult queryResult = builder.build();

        log.debug("Converted cached result to OpResult: {} rows, {} columns, resultSetUUID={}",
                results.size(), cachedResult.getColumnNames().size(), resultSetUUID);

        return OpResult.newBuilder()
                .setQueryResult(queryResult.build())
                .build();
    }

    /**
     * Converts cached query result directly without requiring a result set UUID.
     * Used for simple cache hits that return all data in one response.
     *
     * @param cachedResult The cached result from Caffeine cache
     * @return OpResult containing the cached data
     */
    public static OpResult convertToOpResult(CachedQueryResult cachedResult) {
        return convertToOpResult(cachedResult, null, true);
    }
}

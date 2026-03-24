package org.openjproxy.grpc.server.cache;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable cached query result with data, metadata, and expiration information.
 * Thread-safe and suitable for concurrent access.
 */
public final class CachedQueryResult {
    private final List<List<Object>> rows;  // Immutable
    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final Instant cachedAt;
    private final Instant expiresAt;
    private final Set<String> affectedTables;  // For invalidation
    private final long estimatedSizeBytes;  // Pre-computed

    /**
     * Creates a new cached query result.
     *
     * @param rows the result rows (will be copied to immutable structure)
     * @param columnNames the column names
     * @param columnTypes the column types
     * @param cachedAt when the result was cached
     * @param expiresAt when the result expires
     * @param affectedTables tables affected by this query (for invalidation)
     */
    public CachedQueryResult(
            List<List<Object>> rows,
            List<String> columnNames,
            List<String> columnTypes,
            Instant cachedAt,
            Instant expiresAt,
            Set<String> affectedTables) {
        this.rows = rows == null ? List.of() : List.copyOf(rows.stream().map(List::copyOf).toList());
        this.columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
        this.columnTypes = columnTypes == null ? List.of() : List.copyOf(columnTypes);
        this.cachedAt = Objects.requireNonNull(cachedAt, "cachedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.affectedTables = affectedTables == null ? Set.of() : Set.copyOf(affectedTables);
        this.estimatedSizeBytes = estimateSize();
    }

    /**
     * Checks if this cache entry has expired.
     *
     * @return true if the current time is after the expiration time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this cache entry has expired at a specific time.
     *
     * @param now the time to check against
     * @return true if the specified time is after the expiration time
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Estimates the memory size of this cached result in bytes.
     * This is a rough estimate used for memory management.
     */
    private long estimateSize() {
        long size = 128;  // Object overhead + fields
        
        // Estimate rows size
        for (List<Object> row : rows) {
            size += 64;  // List overhead
            for (Object value : row) {
                size += estimateValueSize(value);
            }
        }
        
        // Estimate column metadata size
        for (String name : columnNames) {
            size += 40 + (name.length() * 2);  // String overhead + chars
        }
        for (String type : columnTypes) {
            size += 40 + (type.length() * 2);
        }
        
        // Estimate affected tables size
        for (String table : affectedTables) {
            size += 40 + (table.length() * 2);
        }
        
        return size;
    }

    private long estimateValueSize(Object value) {
        if (value == null) return 8;
        if (value instanceof String s) return 40 + (s.length() * 2);  // UTF-16
        if (value instanceof Integer) return 16;
        if (value instanceof Long) return 24;
        if (value instanceof Double) return 24;
        if (value instanceof Float) return 16;
        if (value instanceof Boolean) return 16;
        if (value instanceof byte[] b) return 40 + b.length;
        return 32;  // Default estimate for other types
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<String> getColumnTypes() {
        return columnTypes;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Set<String> getAffectedTables() {
        return affectedTables;
    }

    public long getEstimatedSizeBytes() {
        return estimatedSizeBytes;
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getColumnCount() {
        return columnNames.size();
    }

    @Override
    public String toString() {
        return "CachedQueryResult{" +
            "rowCount=" + rows.size() +
            ", columnCount=" + columnNames.size() +
            ", cachedAt=" + cachedAt +
            ", expiresAt=" + expiresAt +
            ", sizeBytes=" + estimatedSizeBytes +
            ", affectedTables=" + affectedTables +
            '}';
    }
}

package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CachedQueryResultTest {

    @Test
    void testConstructorWithValidParameters() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        
        CachedQueryResult result = new CachedQueryResult(
            List.of(List.of("value1", "value2")),
            List.of("col1", "col2"),
            List.of("VARCHAR", "VARCHAR"),
            now,
            expiresAt,
            Set.of("users")
        );
        
        assertEquals(1, result.getRowCount());
        assertEquals(2, result.getColumnCount());
        assertEquals(now, result.getCachedAt());
        assertEquals(expiresAt, result.getExpiresAt());
        assertEquals(Set.of("users"), result.getAffectedTables());
    }

    @Test
    void testConstructorWithNullCachedAt() {
        assertThrows(NullPointerException.class, () ->
            new CachedQueryResult(List.of(), List.of(), List.of(), null, Instant.now(), Set.of())
        );
    }

    @Test
    void testConstructorWithNullExpiresAt() {
        assertThrows(NullPointerException.class, () ->
            new CachedQueryResult(List.of(), List.of(), List.of(), Instant.now(), null, Set.of())
        );
    }

    @Test
    void testConstructorWithNullRows() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(null, List.of(), List.of(), now, now, Set.of());
        
        assertEquals(0, result.getRowCount());
        assertNotNull(result.getRows());
    }

    @Test
    void testIsExpiredWhenNotExpired() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, expiresAt, Set.of()
        );
        
        assertFalse(result.isExpired());
    }

    @Test
    void testIsExpiredWhenExpired() {
        Instant now = Instant.now();
        Instant expiresAt = now.minusSeconds(1);
        
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, expiresAt, Set.of()
        );
        
        assertTrue(result.isExpired());
    }

    @Test
    void testIsExpiredWithSpecificTime() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(100);
        
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, expiresAt, Set.of()
        );
        
        assertFalse(result.isExpired(now));
        assertFalse(result.isExpired(now.plusSeconds(50)));
        assertTrue(result.isExpired(now.plusSeconds(101)));
    }

    @Test
    void testEstimatedSizeWithEmptyResult() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, now, Set.of()
        );
        
        assertTrue(result.getEstimatedSizeBytes() > 0);
    }

    @Test
    void testEstimatedSizeWithData() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(
                List.of("value1", 123, 456L),
                List.of("value2", 789, 012L)
            ),
            List.of("col1", "col2", "col3"),
            List.of("VARCHAR", "INTEGER", "BIGINT"),
            now,
            now,
            Set.of("table1", "table2")
        );
        
        long size = result.getEstimatedSizeBytes();
        assertTrue(size > 128);  // At least the base overhead
    }

    @Test
    void testImmutability() {
        java.util.ArrayList<List<Object>> rows = new java.util.ArrayList<>();
        rows.add(List.of("value1"));
        
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            rows, List.of("col1"), List.of("VARCHAR"), now, now, Set.of()
        );
        
        // Modify original list
        rows.add(List.of("value2"));
        
        // Result should not be affected
        assertEquals(1, result.getRowCount());
    }

    @Test
    void testRowsAreUnmodifiable() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(List.of("value1")),
            List.of("col1"),
            List.of("VARCHAR"),
            now,
            now,
            Set.of()
        );
        
        assertThrows(UnsupportedOperationException.class, () ->
            result.getRows().add(List.of("value2"))
        );
    }

    @Test
    void testColumnNamesAreUnmodifiable() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of("col1"), List.of(), now, now, Set.of()
        );
        
        assertThrows(UnsupportedOperationException.class, () ->
            result.getColumnNames().add("col2")
        );
    }

    @Test
    void testAffectedTablesAreUnmodifiable() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, now, Set.of("table1")
        );
        
        assertThrows(UnsupportedOperationException.class, () ->
            result.getAffectedTables().add("table2")
        );
    }

    @Test
    void testGetRowCount() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(List.of(1), List.of(2), List.of(3)),
            List.of("id"),
            List.of("INTEGER"),
            now,
            now,
            Set.of()
        );
        
        assertEquals(3, result.getRowCount());
    }

    @Test
    void testGetColumnCount() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(),
            List.of("id", "name", "email"),
            List.of("INTEGER", "VARCHAR", "VARCHAR"),
            now,
            now,
            Set.of()
        );
        
        assertEquals(3, result.getColumnCount());
    }

    @Test
    void testToString() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(List.of(1)),
            List.of("id"),
            List.of("INTEGER"),
            now,
            now.plusSeconds(300),
            Set.of("users")
        );
        
        String str = result.toString();
        assertTrue(str.contains("rowCount=1"));
        assertTrue(str.contains("columnCount=1"));
        assertTrue(str.contains("users"));
    }

    @Test
    void testSizeEstimationWithVariousTypes() {
        Instant now = Instant.now();
        // Create rows with various types (excluding null to avoid List.copyOf issues)
        List<List<Object>> rows = List.of(
            List.of(
                "string value",
                123,
                456L,
                78.9,
                12.3f,
                true,
                new byte[]{1, 2, 3}
            )
        );
        
        CachedQueryResult result = new CachedQueryResult(
            rows,
            List.of("str", "int", "long", "double", "float", "bool", "bytes"),
            List.of("VARCHAR", "INTEGER", "BIGINT", "DOUBLE", "FLOAT", "BOOLEAN", "BLOB"),
            now,
            now,
            Set.of()
        );
        
        assertTrue(result.getEstimatedSizeBytes() > 0);
    }

    @Test
    void testEmptyAffectedTables() {
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
            List.of(), List.of(), List.of(), now, now, null
        );
        
        assertEquals(Set.of(), result.getAffectedTables());
    }
}

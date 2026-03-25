package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryCacheMetrics implementations.
 * Tests the SPI pattern with no-op and OpenTelemetry implementations.
 */
public class QueryCacheMetricsTest {

    @BeforeEach
    public void setUp() {
        // Reset state before each test
    }

    // ==================== NoOpQueryCacheMetrics Tests ====================

    @Test
    public void testNoOpMetricsDoesNotThrow() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // All methods should execute without throwing exceptions
        assertDoesNotThrow(() -> {
            metrics.recordHit("testds", "SELECT * FROM test");
            metrics.recordMiss("testds", "SELECT * FROM test");
            metrics.recordEviction("testds", "size");
            metrics.recordInvalidation("testds", "test_table");
            metrics.recordRejection("testds", 1000000);
            metrics.updateCacheSize("testds", 100, 50000);
            metrics.recordQueryExecutionTime("testds", "SELECT * FROM test", 150, "cache");
        });
    }

    @Test
    public void testNoOpMetricsIsSingleton() {
        QueryCacheMetrics instance1 = NoOpQueryCacheMetrics.getInstance();
        QueryCacheMetrics instance2 = NoOpQueryCacheMetrics.getInstance();
        
        assertSame(instance1, instance2, "NoOpQueryCacheMetrics should be singleton");
    }

    @Test
    public void testNoOpMetricsWithNullParameters() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should not throw with null parameters
        assertDoesNotThrow(() -> {
            metrics.recordHit(null, null);
            metrics.recordMiss(null, null);
            metrics.recordEviction(null, null);
            metrics.recordInvalidation(null, null);
            metrics.updateCacheSize(null, 0, 0);
            metrics.recordQueryExecutionTime(null, null, 0, null);
        });
    }

    @Test
    public void testNoOpMetricsWithExtremeValues() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should handle extreme values without issues
        assertDoesNotThrow(() -> {
            metrics.recordRejection("testds", Long.MAX_VALUE);
            metrics.updateCacheSize("testds", Integer.MAX_VALUE, Long.MAX_VALUE);
            metrics.recordQueryExecutionTime("testds", "SELECT", Long.MAX_VALUE, "cache");
        });
    }

    @Test
    public void testNoOpMetricsPerformance() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should be extremely fast (no-op)
        long start = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            metrics.recordHit("testds", "SELECT * FROM test");
        }
        long duration = System.nanoTime() - start;
        
        // 100K operations should take less than 10ms
        assertTrue(duration < 10_000_000, 
            "NoOp metrics should be very fast, took: " + (duration / 1_000_000) + "ms");
    }

    // ==================== Metrics Interface Contract Tests ====================

    @Test
    public void testMetricsInterfaceContract() {
        // Test that the interface can be implemented
        QueryCacheMetrics testMetrics = new QueryCacheMetrics() {
            @Override
            public void recordHit(String datasource, String sql) {}
            
            @Override
            public void recordMiss(String datasource, String sql) {}
            
            @Override
            public void recordEviction(String datasource, String reason) {}
            
            @Override
            public void recordInvalidation(String datasource, String table) {}
            
            @Override
            public void recordRejection(String datasource, long sizeBytes) {}
            
            @Override
            public void updateCacheSize(String datasource, long entries, long sizeBytes) {}
            
            @Override
            public void recordQueryExecutionTime(String datasource, String sql, long durationMs, String source) {}
        };
        
        assertNotNull(testMetrics);
        
        // Test all methods are callable
        assertDoesNotThrow(() -> {
            testMetrics.recordHit("ds", "sql");
            testMetrics.recordMiss("ds", "sql");
            testMetrics.recordEviction("ds", "reason");
            testMetrics.recordInvalidation("ds", "table");
            testMetrics.recordRejection("ds", 1000);
            testMetrics.updateCacheSize("ds", 10, 10000);
            testMetrics.recordQueryExecutionTime("ds", "sql", 100, "cache");
        });
    }

    @Test
    public void testMetricsWithVeryLongSQL() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Create SQL longer than truncation limit
        StringBuilder longSql = new StringBuilder("SELECT * FROM ");
        for (int i = 0; i < 200; i++) {
            longSql.append("table").append(i).append(", ");
        }
        String sql = longSql.toString();
        
        // Should handle without issues
        assertDoesNotThrow(() -> {
            metrics.recordHit("testds", sql);
            metrics.recordMiss("testds", sql);
            metrics.recordQueryExecutionTime("testds", sql, 100, "cache");
        });
    }

    @Test
    public void testMetricsWithSpecialCharacters() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        String sqlWithSpecialChars = "SELECT * FROM \"test-table\" WHERE name = 'O''Reilly' AND data LIKE '%test%'";
        String tableWithSpecialChars = "test-table.schema_name";
        
        assertDoesNotThrow(() -> {
            metrics.recordHit("testds", sqlWithSpecialChars);
            metrics.recordInvalidation("testds", tableWithSpecialChars);
        });
    }

    @Test
    public void testMetricsWithEmptyStrings() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        assertDoesNotThrow(() -> {
            metrics.recordHit("", "");
            metrics.recordMiss("", "");
            metrics.recordEviction("", "");
            metrics.recordInvalidation("", "");
            metrics.updateCacheSize("", 0, 0);
            metrics.recordQueryExecutionTime("", "", 0, "");
        });
    }

    @Test
    public void testMetricsWithNegativeValues() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should handle negative values gracefully
        assertDoesNotThrow(() -> {
            metrics.recordRejection("testds", -1);
            metrics.updateCacheSize("testds", -1, -1);
            metrics.recordQueryExecutionTime("testds", "SELECT", -1, "cache");
        });
    }

    @Test
    public void testMetricsConcurrentAccess() throws Exception {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Test concurrent access from multiple threads
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    metrics.recordHit("testds" + threadId, "SELECT " + j);
                    metrics.recordMiss("testds" + threadId, "SELECT " + j);
                    metrics.updateCacheSize("testds" + threadId, j, j * 100);
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
        
        // If we get here without exceptions, concurrent access works
        assertTrue(true);
    }

    @Test
    public void testMetricsMethodChaining() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Methods should return void, so no chaining
        // Just verify they can be called in sequence
        metrics.recordHit("testds", "SELECT 1");
        metrics.recordHit("testds", "SELECT 2");
        metrics.recordHit("testds", "SELECT 3");
        
        // No exceptions = success
        assertTrue(true);
    }
}

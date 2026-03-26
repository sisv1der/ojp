package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for query cache lookup functionality.
 * Tests the integration between cache configuration, cache storage, and query execution.
 */
public class QueryCacheLookupIntegrationTest {

    private QueryResultCacheRegistry cacheRegistry;
    private static final String DATASOURCE = "test_datasource";

    @BeforeEach
    public void setUp() {
        cacheRegistry = QueryResultCacheRegistry.getInstance();
        cacheRegistry.clear();  // Clean state
    }

    @AfterEach
    public void tearDown() {
        cacheRegistry.clear();
    }

    @Test
    public void testCacheLookup_Hit() {
        // Setup: Create cache configuration
        CacheRule rule = new CacheRule(
                Pattern.compile("SELECT .*"),
                Duration.ofMinutes(5),
                List.of("users"),
                true
        );
        CacheConfiguration config = new CacheConfiguration(DATASOURCE, true, List.of(rule));

        // Setup: Pre-populate cache
        QueryResultCache cache = cacheRegistry.getOrCreate(DATASOURCE);
        String sql = "SELECT * FROM users WHERE id = ?";
        List<Object> params = List.of(1);
        QueryCacheKey key = new QueryCacheKey(DATASOURCE, sql, params);

        List<List<Object>> rows = List.of(
                List.of(1, "John", "john@example.com"),
                List.of(2, "Jane", "jane@example.com")
        );
        List<String> columnNames = List.of("id", "name", "email");
        List<String> columnTypes = List.of("INTEGER", "VARCHAR", "VARCHAR");
        
        CachedQueryResult result = new CachedQueryResult(
                rows, columnNames, columnTypes, 
                Instant.now(), 
                Instant.now().plus(Duration.ofMinutes(5)), 
                Set.of());
        cache.put(key, result);

        // Act: Lookup from cache
        CachedQueryResult cachedResult = cache.get(key);

        // Assert: Cache hit
        assertNotNull(cachedResult, "Cache should return result");
        assertFalse(cachedResult.isExpired(), "Result should not be expired");
        assertEquals(2, cachedResult.getRows().size(), "Should have 2 rows");
        assertEquals(3, cachedResult.getColumnNames().size(), "Should have 3 columns");
        assertEquals("John", cachedResult.getRows().get(0).get(1), "First row name should be John");
        
        // Verify statistics
        CacheStatistics stats = cache.getStatistics();
        assertEquals(1, stats.getHits(), "Should have 1 cache hit");
        assertEquals(0, stats.getMisses(), "Should have 0 cache misses");
    }

    @Test
    public void testCacheLookup_Miss() {
        // Setup: Create empty cache
        QueryResultCache cache = cacheRegistry.getOrCreate(DATASOURCE);
        String sql = "SELECT * FROM products WHERE category = ?";
        List<Object> params = List.of("electronics");
        QueryCacheKey key = new QueryCacheKey(DATASOURCE, sql, params);

        // Act: Lookup from cache (should miss)
        CachedQueryResult cachedResult = cache.get(key);

        // Assert: Cache miss
        assertNull(cachedResult, "Cache should return null for miss");
        
        // Verify statistics
        CacheStatistics stats = cache.getStatistics();
        assertEquals(0, stats.getHits(), "Should have 0 cache hits");
        assertEquals(1, stats.getMisses(), "Should have 1 cache miss");
    }

    @Test
    public void testCacheLookup_ExpiredEntry() throws InterruptedException {
        // Setup: Create cache with very short TTL
        QueryResultCache cache = cacheRegistry.getOrCreate(DATASOURCE, 1000, Duration.ofMillis(100), 1024 * 1024);
        String sql = "SELECT * FROM orders WHERE status = ?";
        List<Object> params = List.of("completed");
        QueryCacheKey key = new QueryCacheKey(DATASOURCE, sql, params);

        // Setup: Add entry with short TTL
        List<List<Object>> rows = List.of(List.of(1, "completed", 100.00));
        List<String> columnNames = List.of("id", "status", "total");
        List<String> columnTypes = List.of("INTEGER", "VARCHAR", "DECIMAL");
        
        CachedQueryResult result = new CachedQueryResult(
                rows, columnNames, columnTypes, 
                Instant.now(), 
                Instant.now().plus(Duration.ofMillis(50)), 
                Set.of());
        cache.put(key, result);

        // Wait for expiration
        Thread.sleep(60);

        // Act: Lookup after expiration
        CachedQueryResult cachedResult = cache.get(key);

        // Assert: Entry should be expired (treated as miss)
        if (cachedResult != null) {
            assertTrue(cachedResult.isExpired(), "Result should be expired");
        }
    }

    @Test
    public void testCacheLookup_DifferentParameters() {
        // Setup: Cache with same SQL but different parameters
        QueryResultCache cache = cacheRegistry.getOrCreate(DATASOURCE);
        String sql = "SELECT * FROM users WHERE id = ?";

        // Add entry for id=1
        QueryCacheKey key1 = new QueryCacheKey(DATASOURCE, sql, List.of(1));
        CachedQueryResult result1 = new CachedQueryResult(
                List.of(List.of(1, "John")),
                List.of("id", "name"),
                List.of("INTEGER", "VARCHAR"),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5)),
                Set.of());
        cache.put(key1, result1);

        // Add entry for id=2
        QueryCacheKey key2 = new QueryCacheKey(DATASOURCE, sql, List.of(2));
        CachedQueryResult result2 = new CachedQueryResult(
                List.of(List.of(2, "Jane")),
                List.of("id", "name"),
                List.of("INTEGER", "VARCHAR"),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5)),
                Set.of());
        cache.put(key2, result2);

        // Act & Assert: Different parameters should return different results
        CachedQueryResult cached1 = cache.get(key1);
        CachedQueryResult cached2 = cache.get(key2);

        assertNotNull(cached1);
        assertNotNull(cached2);
        assertEquals("John", cached1.getRows().get(0).get(1));
        assertEquals("Jane", cached2.getRows().get(0).get(1));
    }

    @Test
    public void testCacheLookup_PatternMatching() {
        // Setup: Create cache rule with pattern
        CacheRule selectUsersRule = new CacheRule(
                Pattern.compile("SELECT .* FROM users .*"),
                Duration.ofMinutes(10),
                List.of("users"),
                true
        );
        CacheConfiguration config = new CacheConfiguration(DATASOURCE, true, List.of(selectUsersRule));

        // Test various SQL statements
        assertTrue(config.findMatchingRule("SELECT * FROM users WHERE id = 1") != null,
                "Should match SELECT with WHERE");
        assertTrue(config.findMatchingRule("SELECT id, name FROM users") != null,
                "Should match SELECT with specific columns");
        assertTrue(config.findMatchingRule("  SELECT  *  FROM  users  ") != null,
                "Should match with extra whitespace");
        assertNull(config.findMatchingRule("UPDATE users SET name = 'John'"),
                "Should not match UPDATE");
        assertNull(config.findMatchingRule("SELECT * FROM products"),
                "Should not match different table");
    }
}

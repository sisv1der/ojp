package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheConfigurationParser}.
 */
class CacheConfigurationParserTest {
    
    @BeforeEach
    @AfterEach
    void clearProperties() {
        // Clear all test properties before and after each test
        System.getProperties().stringPropertyNames().stream()
            .filter(key -> key.startsWith("testds."))
            .forEach(System::clearProperty);
    }
    
    @Test
    void testParseDisabledConfiguration() {
        // No enabled property means caching is disabled
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertNotNull(config);
        assertFalse(config.isEnabled());
        assertEquals("testds", config.getDatasourceName());
        assertTrue(config.getRules().isEmpty());
    }
    
    @Test
    void testParseDisabledExplicitly() {
        System.setProperty("testds.ojp.cache.enabled", "false");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertFalse(config.isEnabled());
        assertTrue(config.getRules().isEmpty());
    }
    
    @Test
    void testParseEnabledWithNoRules() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        // No queries defined
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("no query rules defined"));
    }
    
    @Test
    void testParseSingleRule() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "600s");
        System.setProperty("testds.ojp.cache.queries.1.invalidateOn", "products");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertTrue(config.isEnabled());
        assertEquals(1, config.getRules().size());
        
        CacheRule rule = config.getRules().get(0);
        assertTrue(rule.matches("SELECT * FROM products WHERE id = 1"));
        assertEquals(Duration.ofSeconds(600), rule.getTtl());
        assertEquals(List.of("products"), rule.getInvalidateOn());
        assertTrue(rule.isEnabled());
    }
    
    @Test
    void testParseMultipleRules() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "600s");
        System.setProperty("testds.ojp.cache.queries.1.invalidateOn", "products");
        
        System.setProperty("testds.ojp.cache.queries.2.pattern", "SELECT .* FROM users.*");
        System.setProperty("testds.ojp.cache.queries.2.ttl", "300s");
        System.setProperty("testds.ojp.cache.queries.2.invalidateOn", "users");
        
        System.setProperty("testds.ojp.cache.queries.3.pattern", "SELECT .* FROM orders.*");
        System.setProperty("testds.ojp.cache.queries.3.ttl", "10m");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertEquals(3, config.getRules().size());
        
        // Rules should be in index order (1, 2, 3)
        CacheRule rule1 = config.getRules().get(0);
        assertTrue(rule1.matches("SELECT * FROM products WHERE id = 1"));
        assertEquals(Duration.ofSeconds(600), rule1.getTtl());
        
        CacheRule rule2 = config.getRules().get(1);
        assertTrue(rule2.matches("SELECT * FROM users WHERE id = 1"));
        assertEquals(Duration.ofSeconds(300), rule2.getTtl());
        
        CacheRule rule3 = config.getRules().get(2);
        assertTrue(rule3.matches("SELECT * FROM orders WHERE id = 1"));
        assertEquals(Duration.ofMinutes(10), rule3.getTtl());
    }
    
    @Test
    void testParseDistributeFlag() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.distribute", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertTrue(config.isEnabled());
    }
    
    @Test
    void testParseTtlFormats() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        
        // Seconds
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        // Minutes
        System.setProperty("testds.ojp.cache.queries.2.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.2.ttl", "10m");
        
        // Hours
        System.setProperty("testds.ojp.cache.queries.3.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.3.ttl", "2h");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertEquals(Duration.ofSeconds(300), config.getRules().get(0).getTtl());
        assertEquals(Duration.ofMinutes(10), config.getRules().get(1).getTtl());
        assertEquals(Duration.ofHours(2), config.getRules().get(2).getTtl());
    }
    
    @Test
    void testParseDefaultTtl() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        // No TTL specified, should default to 300s
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertEquals(Duration.ofSeconds(300), config.getRules().get(0).getTtl());
    }
    
    @Test
    void testParseInvalidateOnMultipleTables() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        System.setProperty("testds.ojp.cache.queries.1.invalidateOn", "products,product_prices,categories");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertEquals(List.of("products", "product_prices", "categories"), 
                     config.getRules().get(0).getInvalidateOn());
    }
    
    @Test
    void testParseInvalidateOnWithSpaces() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        System.setProperty("testds.ojp.cache.queries.1.invalidateOn", " products , product_prices , categories ");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertEquals(List.of("products", "product_prices", "categories"), 
                     config.getRules().get(0).getInvalidateOn());
    }
    
    @Test
    void testParseInvalidateOnEmpty() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        // No invalidateOn property
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertTrue(config.getRules().get(0).getInvalidateOn().isEmpty());
    }
    
    @Test
    void testParseInvalidRegexPattern() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "[invalid(regex");  // Malformed regex
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("Invalid regex pattern"));
    }
    
    @Test
    void testParseMissingPattern() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        // No pattern property
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        // Should complain about missing required pattern property
        assertTrue(ex.getMessage().contains("Missing required property") &&
                   ex.getMessage().contains("pattern"),
                   "Actual message: " + ex.getMessage());
    }
    
    @Test
    void testParseInvalidTtlFormat() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "invalid");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("Invalid TTL format"));
    }
    
    @Test
    void testParseInvalidTtlNumber() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "abc123s");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("Invalid TTL format"));
    }
    
    @Test
    void testParseNegativeTtl() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "-100s");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("TTL must be positive"));
    }
    
    @Test
    void testParseZeroTtl() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "0s");
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CacheConfigurationParser.parse("testds")
        );
        
        assertTrue(ex.getMessage().contains("TTL must be positive"));
    }
    
    @Test
    void testParseRuleEnabledFlag() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        System.setProperty("testds.ojp.cache.queries.1.enabled", "false");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertFalse(config.getRules().get(0).isEnabled());
    }
    
    @Test
    void testParseRuleEnabledDefaultTrue() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        // No enabled property - should default to true
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        assertTrue(config.getRules().get(0).isEnabled());
    }
    
    @Test
    void testParseNullDatasourceName() {
        assertThrows(
            NullPointerException.class,
            () -> CacheConfigurationParser.parse(null)
        );
    }
    
    @Test
    void testParseSkipsInvalidIndices() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        // Invalid indices (should be skipped)
        System.setProperty("testds.ojp.cache.queries.0.pattern", "SELECT .*");  // Zero index
        System.setProperty("testds.ojp.cache.queries.-1.pattern", "SELECT .*");  // Negative
        System.setProperty("testds.ojp.cache.queries.abc.pattern", "SELECT .*");  // Non-numeric
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        // Should only have rule 1
        assertEquals(1, config.getRules().size());
    }
    
    @Test
    void testParseNonSequentialIndices() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        
        // Non-sequential indices (1, 5, 10)
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM table1.*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        System.setProperty("testds.ojp.cache.queries.5.pattern", "SELECT .* FROM table5.*");
        System.setProperty("testds.ojp.cache.queries.5.ttl", "300s");
        
        System.setProperty("testds.ojp.cache.queries.10.pattern", "SELECT .* FROM table10.*");
        System.setProperty("testds.ojp.cache.queries.10.ttl", "300s");
        
        CacheConfiguration config = CacheConfigurationParser.parse("testds");
        
        // Should have all 3 rules in sorted order
        assertEquals(3, config.getRules().size());
        assertTrue(config.getRules().get(0).matches("SELECT * FROM table1 WHERE id = 1"));
        assertTrue(config.getRules().get(1).matches("SELECT * FROM table5 WHERE id = 1"));
        assertTrue(config.getRules().get(2).matches("SELECT * FROM table10 WHERE id = 1"));
    }
}

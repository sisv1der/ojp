package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheConfigurationValidator.
 */
class CacheConfigurationValidatorTest {
    
    @Test
    void testValidConfiguration() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(
                new CacheRule("SELECT .* FROM products.*", Duration.ofMinutes(10), List.of("products"), true)
            )
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
    }
    
    @Test
    void testNullConfiguration() {
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(null);
        
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("cannot be null"));
    }
    
    @Test
    void testNullDatasourceName() {
        CacheConfiguration config = new CacheConfiguration(
            null,
            true,
            List.of(new CacheRule("SELECT .*", Duration.ofMinutes(10), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Datasource name")));
    }
    
    @Test
    void testEmptyRules() {
        CacheConfiguration config = new CacheConfiguration("testds", true, List.of());
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("No cache rules"));
    }
    
    @Test
    void testInvalidRegexPattern() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule("[invalid(regex", Duration.ofMinutes(10), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid regex pattern")));
    }
    
    @Test
    void testNegativeTtl() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule("SELECT .*", Duration.ofSeconds(-1), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("TTL must be positive")));
    }
    
    @Test
    void testZeroTtl() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule("SELECT .*", Duration.ZERO, List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("TTL must be positive")));
    }
    
    @Test
    void testVeryShortTtl() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule("SELECT .*", Duration.ofSeconds(5), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("TTL very short")));
    }
    
    @Test
    void testVeryLongTtl() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule("SELECT .*", Duration.ofHours(48), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("TTL very long")));
    }
    
    @Test
    void testSqlInjectionInTableName() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(
                "SELECT .*", 
                Duration.ofMinutes(10), 
                List.of("products; DROP TABLE users--"), 
                true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> 
            e.contains("Suspicious table name") && e.contains("SQL injection")));
    }
    
    @Test
    void testQuotesInTableName() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(
                "SELECT .*", 
                Duration.ofMinutes(10), 
                List.of("table'name"), 
                true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Suspicious table name")));
    }
    
    @Test
    void testTableNameWithSpace() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(
                "SELECT .*", 
                Duration.ofMinutes(10), 
                List.of("my table"), 
                true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("contains space")));
    }
    
    @Test
    void testOverlyBroadPattern() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(".*", Duration.ofMinutes(10), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("matches all queries")));
    }
    
    @Test
    void testVeryLongPattern() {
        String longPattern = "SELECT .*".repeat(100);  // Very long pattern
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(longPattern, Duration.ofMinutes(10), List.of(), true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());  // Valid but with warning
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Pattern very long")));
    }
    
    @Test
    void testMultipleRulesWithErrors() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(
                new CacheRule("[invalid", Duration.ofMinutes(10), List.of(), true),
                new CacheRule("SELECT .*", Duration.ofSeconds(-1), List.of(), true),
                new CacheRule("SELECT .*", Duration.ofMinutes(10), List.of("bad;table"), true)
            )
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().size() >= 3);  // At least 3 errors
    }
    
    @Test
    void testFormattedMessages() {
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(
                new CacheRule("[invalid", Duration.ofSeconds(5), List.of(), true)
            )
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        String formatted = result.getFormattedMessages();
        assertFalse(formatted.isEmpty());
        assertTrue(formatted.contains("Errors:"));
        assertTrue(formatted.contains("Warnings:"));
    }
    
    @Test
    void testSchemaQualifiedTableName() {
        // Schema-qualified table names should be allowed
        CacheConfiguration config = new CacheConfiguration(
            "testds",
            true,
            List.of(new CacheRule(
                "SELECT .*", 
                Duration.ofMinutes(10), 
                List.of("schema.table_name"), 
                true))
        );
        
        CacheConfigurationValidator.ValidationResult result = 
            CacheConfigurationValidator.validate(config);
        
        assertTrue(result.isValid());
        assertTrue(result.getWarnings().isEmpty());
    }
}

package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for automatic SQL dialect translation.
 * Tests translation between different database dialects.
 */
public class SqlDialectTranslationTest {
    
    /**
     * Test PostgreSQL to MySQL translation.
     */
    @Test
    public void testPostgreSQLToMySQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "POSTGRESQL", "MYSQL", true, false, null
        );
        
        // Test basic SELECT with PostgreSQL-specific syntax
        String postgresSQL = "SELECT * FROM users WHERE name ILIKE '%john%'";
        SqlEnhancementResult result = engine.enhance(postgresSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        assertTrue(result.isModified(), "SQL should be modified by translation");
        
        System.out.println("PostgreSQL -> MySQL:");
        System.out.println("  Original: " + postgresSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test MySQL to PostgreSQL translation.
     */
    @Test
    public void testMySQLToPostgreSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "MYSQL", "POSTGRESQL", true, false, null
        );
        
        // Test basic SELECT with MySQL backticks
        String mysqlSQL = "SELECT `id`, `name`, `email` FROM `users` WHERE `status` = 'active'";
        SqlEnhancementResult result = engine.enhance(mysqlSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        // Note: Translation may or may not modify depending on dialect differences
        
        System.out.println("MySQL -> PostgreSQL:");
        System.out.println("  Original: " + mysqlSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
        System.out.println("  Modified: " + result.isModified());
    }
    
    /**
     * Test Oracle to PostgreSQL translation.
     */
    @Test
    public void testOracleToPostgreSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "ORACLE", "POSTGRESQL", true, false, null
        );
        
        // Test Oracle-style dual table
        String oracleSQL = "SELECT SYSDATE FROM DUAL";
        SqlEnhancementResult result = engine.enhance(oracleSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("Oracle -> PostgreSQL:");
        System.out.println("  Original: " + oracleSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test SQL Server to MySQL translation.
     */
    @Test
    public void testSQLServerToMySQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "SQL_SERVER", "MYSQL", true, false, null
        );
        
        // Test SQL Server-style TOP clause
        String sqlServerSQL = "SELECT TOP 10 id, name FROM users ORDER BY created_at DESC";
        SqlEnhancementResult result = engine.enhance(sqlServerSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("SQL Server -> MySQL:");
        System.out.println("  Original: " + sqlServerSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test H2 to PostgreSQL translation.
     */
    @Test
    public void testH2ToPostgreSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "H2", "POSTGRESQL", true, false, null
        );
        
        // Test basic query
        String h2SQL = "SELECT id, name, created_at FROM users WHERE status = 'active' LIMIT 100";
        SqlEnhancementResult result = engine.enhance(h2SQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("H2 -> PostgreSQL:");
        System.out.println("  Original: " + h2SQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test PostgreSQL to Oracle translation with JOIN.
     */
    @Test
    public void testPostgreSQLToOracleWithJoin() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "POSTGRESQL", "ORACLE", true, false, null
        );
        
        // Test JOIN query
        String postgresSQL = "SELECT u.id, u.name, o.order_date FROM users u " +
                           "INNER JOIN orders o ON u.id = o.user_id " +
                           "WHERE u.status = 'active'";
        SqlEnhancementResult result = engine.enhance(postgresSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        assertTrue(result.isModified(), "SQL should be modified by translation");
        
        System.out.println("PostgreSQL -> Oracle (with JOIN):");
        System.out.println("  Original: " + postgresSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test MySQL to SQL Server translation with aggregation.
     */
    @Test
    public void testMySQLToSQLServerWithAggregation() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "MYSQL", "SQL_SERVER", true, false, null
        );
        
        // Test aggregation query
        String mysqlSQL = "SELECT status, COUNT(*) as count FROM users GROUP BY status HAVING count > 10";
        SqlEnhancementResult result = engine.enhance(mysqlSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("MySQL -> SQL Server (with aggregation):");
        System.out.println("  Original: " + mysqlSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test Oracle to MySQL translation with subquery.
     */
    @Test
    public void testOracleToMySQLWithSubquery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "ORACLE", "MYSQL", true, false, null
        );
        
        // Test subquery
        String oracleSQL = "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE status = 'completed')";
        SqlEnhancementResult result = engine.enhance(oracleSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        assertTrue(result.isModified(), "SQL should be modified by translation");
        
        System.out.println("Oracle -> MySQL (with subquery):");
        System.out.println("  Original: " + oracleSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test SQL Server to PostgreSQL translation with CASE expression.
     */
    @Test
    public void testSQLServerToPostgreSQLWithCase() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "SQL_SERVER", "POSTGRESQL", true, false, null
        );
        
        // Test CASE expression
        String sqlServerSQL = "SELECT id, name, " +
                            "CASE WHEN status = 'active' THEN 1 ELSE 0 END as is_active " +
                            "FROM users";
        SqlEnhancementResult result = engine.enhance(sqlServerSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("SQL Server -> PostgreSQL (with CASE):");
        System.out.println("  Original: " + sqlServerSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test H2 to Oracle translation with multiple tables.
     */
    @Test
    public void testH2ToOracleMultipleTables() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "H2", "ORACLE", true, false, null
        );
        
        // Test complex query with multiple tables
        String h2SQL = "SELECT u.id, u.name, o.order_date, p.product_name " +
                      "FROM users u " +
                      "INNER JOIN orders o ON u.id = o.user_id " +
                      "INNER JOIN products p ON o.product_id = p.id " +
                      "WHERE u.status = 'active' AND o.status = 'shipped'";
        SqlEnhancementResult result = engine.enhance(h2SQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("H2 -> Oracle (multiple tables):");
        System.out.println("  Original: " + h2SQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test no translation when target dialect is not set.
     */
    @Test
    public void testNoTranslationWhenTargetDialectNotSet() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "POSTGRESQL", "", true, false, null
        );
        
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        assertFalse(result.isModified(), "SQL should not be modified when no target dialect");
        assertEquals(sql, result.getEnhancedSql(), "SQL should remain unchanged");
        
        System.out.println("No translation (empty target dialect):");
        System.out.println("  Original: " + sql);
        System.out.println("  Result: " + result.getEnhancedSql());
    }
    
    /**
     * Test translation is cached.
     */
    @Test
    public void testTranslationIsCached() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "MYSQL", "POSTGRESQL", true, false, null
        );
        
        String mysqlSQL = "SELECT `id`, `name` FROM `users`";
        
        // First call
        SqlEnhancementResult result1 = engine.enhance(mysqlSQL);
        assertNotNull(result1);
        
        // Second call should use cache
        SqlEnhancementResult result2 = engine.enhance(mysqlSQL);
        assertNotNull(result2);
        
        // Results should be the same
        assertEquals(result1.getEnhancedSql(), result2.getEnhancedSql());
        
        System.out.println("Translation caching test:");
        System.out.println("  Original: " + mysqlSQL);
        System.out.println("  First call: " + result1.getEnhancedSql());
        System.out.println("  Second call (cached): " + result2.getEnhancedSql());
    }
    
    /**
     * Test translation with optimization mode.
     */
    @Test
    public void testTranslationWithOptimization() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "MYSQL", "POSTGRESQL", true, true, null
        );
        
        // Query that can be optimized
        String mysqlSQL = "SELECT `id`, `name` FROM `users` WHERE 1=1 AND `status` = 'active'";
        SqlEnhancementResult result = engine.enhance(mysqlSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        // Note: SQL may or may not be modified depending on optimization and translation
        
        System.out.println("Translation with optimization:");
        System.out.println("  Original: " + mysqlSQL);
        System.out.println("  Translated & optimized: " + result.getEnhancedSql());
        System.out.println("  Modified: " + result.isModified());
        System.out.println("  Optimized: " + result.isOptimized());
    }
    
    /**
     * Test Generic to PostgreSQL translation.
     */
    @Test
    public void testGenericToPostgreSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "POSTGRESQL", true, false, null
        );
        
        String genericSQL = "SELECT id, name, email FROM users WHERE status = 'active' ORDER BY created_at DESC";
        SqlEnhancementResult result = engine.enhance(genericSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("Generic -> PostgreSQL:");
        System.out.println("  Original: " + genericSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test PostgreSQL to MySQL with LIMIT and OFFSET.
     */
    @Test
    public void testPostgreSQLToMySQLWithLimitOffset() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "POSTGRESQL", "MYSQL", true, false, null
        );
        
        String postgresSQL = "SELECT id, name FROM users ORDER BY created_at DESC LIMIT 10 OFFSET 20";
        SqlEnhancementResult result = engine.enhance(postgresSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        assertTrue(result.isModified(), "SQL should be modified by translation");
        
        System.out.println("PostgreSQL -> MySQL (with LIMIT/OFFSET):");
        System.out.println("  Original: " + postgresSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
    
    /**
     * Test Oracle to SQL Server with ROWNUM.
     */
    @Test
    public void testOracleToSQLServerWithRownum() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "ORACLE", "SQL_SERVER", true, false, null
        );
        
        String oracleSQL = "SELECT * FROM users WHERE ROWNUM <= 10";
        SqlEnhancementResult result = engine.enhance(oracleSQL);
        
        assertNotNull(result);
        assertNotNull(result.getEnhancedSql());
        
        System.out.println("Oracle -> SQL Server (with ROWNUM):");
        System.out.println("  Original: " + oracleSQL);
        System.out.println("  Translated: " + result.getEnhancedSql());
    }
}

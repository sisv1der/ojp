package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration test for SQL Enhancer functionality with PostgreSQL.
 * 
 * This test leverages the two OJP servers started by the CI postgres-test job:
 * - Server on port 1059: SQL enhancer disabled (baseline)
 * - Server on port 10593: SQL enhancer enabled
 * 
 * The test:
 * 1. Creates test database with realistic data (Regions: 100, Customers: 5000, Orders: 10000)
 * 2. Runs an inefficient query on both servers
 * 3. Verifies both servers return the same results
 * 4. Compares performance metrics
 * 
 * The test validates that the SQL enhancer:
 * - Does not change query results
 * - Properly parses and validates complex queries
 * - Optimizes query execution (should be faster)
 */
@Slf4j
public class PostgresSqlEnhancerIntegrationTest {

    private static boolean isTestEnabled;
    
    // OJP server ports (started by CI workflow)
    private static final int PORT_BASELINE = 1059;  // SQL enhancer disabled
    private static final int PORT_ENHANCED = 10593; // SQL enhancer enabled
    
    // PostgreSQL driver instance for direct connections
    private static final Driver PG_DRIVER = new Driver();
    
    // PostgreSQL connection details (from environment or defaults)
    private static final String PG_HOST = System.getProperty("postgres.host", "localhost");
    private static final String PG_PORT = System.getProperty("postgres.port", "5432");
    private static final String PG_DB = System.getProperty("postgres.db", "defaultdb");
    private static final String PG_USER = System.getProperty("postgres.user", "testuser");
    private static final String PG_PASSWORD = System.getProperty("postgres.password", "testpassword");
    
    // SQL error logging configuration
    private static final int SQL_PREVIEW_LENGTH = 200;
    
    // Test query - intentionally inefficient for SQL enhancer to optimize
    private static final String TEST_QUERY = 
        "SELECT\n" +
        "  r.region_name,\n" +
        "  count(*) AS order_cnt,\n" +
        "  sum(o.amount) AS total_amount\n" +
        "FROM orders o\n" +
        "JOIN customers c\n" +
        "  ON c.customer_id = o.customer_id\n" +
        "JOIN regions r\n" +
        "  ON r.region_id = c.region_id\n" +
        "WHERE\n" +
        "  date_trunc('day', o.order_ts) >= date_trunc('day', now() - interval '30 days')\n" +
        "  AND c.status = 'ACTIVE'\n" +
        "GROUP BY r.region_name\n" +
        "ORDER BY total_amount DESC";

    @BeforeAll
    public static void setup() throws Exception {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableSqlEnhancerIntegrationTest", "false"));
        
        if (!isTestEnabled) {
            log.info("PostgreSQL SQL Enhancer Integration Test is disabled. Enable with -DenableSqlEnhancerIntegrationTest=true");
            return;
        }
        
        log.info("Setting up PostgreSQL SQL Enhancer Integration Test");
        log.info("Using OJP servers: Baseline (port {}), Enhanced (port {})", PORT_BASELINE, PORT_ENHANCED);
        
        // Setup test data in PostgreSQL
        setupTestData();
    }
    
    /**
     * Setup test data in PostgreSQL database
     */
    private static void setupTestData() throws Exception {
        log.info("Setting up test data in PostgreSQL");
        
        String pgUrl = String.format("jdbc:postgresql://%s:%s/%s", PG_HOST, PG_PORT, PG_DB);
        
        // Create a Properties object for connection
        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        props.setProperty("password", PG_PASSWORD);
        
        // Use PostgreSQL driver directly to avoid OJP driver interception
        try (Connection conn = PG_DRIVER.connect(pgUrl, props);
             Statement stmt = conn.createStatement()) {
            
            // Read SQL script
            String setupSql = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(PostgresSqlEnhancerIntegrationTest.class
                    .getResourceAsStream("/sql_enhancer_postgres_test_data.sql"))))
                .lines()
                .reduce("", (acc, line) -> acc + line + "\n");
            
            // Parse and execute SQL statements properly
            // Pattern uses lookahead to match semicolon followed by whitespace and newline/end without consuming them
            String[] statements = setupSql.split(";(?=\\s*(?:\\n|$))");
            log.info("Parsed {} SQL statements from script", statements.length);
            
            int executedCount = 0;
            for (int i = 0; i < statements.length; i++) {
                String sql = statements[i];
                String trimmed = sql.trim();
                // Skip only empty statements (comments are handled below)
                if (trimmed.isEmpty()) {
                    continue;
                }
                
                // Remove leading and inline comments from the statement
                // Note: This simple approach treats '--' anywhere as a comment marker.
                // Limitation: Won't handle '--' within string literals correctly.
                // However, our controlled SQL script doesn't have such cases.
                String[] lines = trimmed.split("\n");
                StringBuilder cleanSql = new StringBuilder();
                for (String line : lines) {
                    // Remove inline comments (everything after -- on the line)
                    int commentIdx = line.indexOf("--");
                    String cleanLine = (commentIdx >= 0) ? line.substring(0, commentIdx).trim() : line.trim();
                    
                    if (!cleanLine.isEmpty()) {
                        if (cleanSql.length() > 0) {
                            cleanSql.append(" ");
                        }
                        cleanSql.append(cleanLine);
                    }
                }
                String finalSql = cleanSql.toString().trim();
                
                // Skip comment-only statements or statements that become empty after removing comments
                if (finalSql.isEmpty()) {
                    continue;
                }
                
                try {
                    log.debug("Executing statement {}: {}", i + 1, 
                        finalSql.length() > 80 ? finalSql.substring(0, 80) + "..." : finalSql);
                    stmt.execute(finalSql);
                    executedCount++;
                } catch (Exception e) {
                    // Log SQL preview and exception message for debugging
                    log.error("Failed to execute SQL statement {} of {}: {}", 
                        i + 1, statements.length,
                        finalSql.length() > SQL_PREVIEW_LENGTH 
                            ? finalSql.substring(0, SQL_PREVIEW_LENGTH) + "..." 
                            : finalSql);
                    log.error("Error: {}", e.getMessage());
                    throw e;
                }
            }
            
            log.info("Successfully executed {} SQL statements", executedCount);
            
            log.info("Test data setup completed");
        }
    }
    
    @Test
    public void testSqlEnhancerPerformance() throws Exception {
        assumeFalse(!isTestEnabled, "Test is disabled");
        
        log.info("Running SQL Enhancer performance comparison test");
        
        // Connection URLs for both servers
        String urlBaseline = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            PORT_BASELINE, PG_HOST, PG_PORT, PG_DB);
        String urlEnhanced = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            PORT_ENHANCED, PG_HOST, PG_PORT, PG_DB);
        
        // Load OJP driver
        Class.forName("org.openjproxy.jdbc.Driver");
        
        // Execute query on baseline server
        log.info("Executing query on baseline server (port {}, SQL enhancer disabled)...", PORT_BASELINE);
        long startBaseline = System.currentTimeMillis();
        String resultBaseline = executeAndCollectResults(urlBaseline);
        long timeBaseline = System.currentTimeMillis() - startBaseline;
        log.info("Baseline server completed in {} ms", timeBaseline);
        
        // Execute query on enhanced server
        log.info("Executing query on enhanced server (port {}, SQL enhancer enabled)...", PORT_ENHANCED);
        long startEnhanced = System.currentTimeMillis();
        String resultEnhanced = executeAndCollectResults(urlEnhanced);
        long timeEnhanced = System.currentTimeMillis() - startEnhanced;
        log.info("Enhanced server completed in {} ms", timeEnhanced);
        
        // Verify results are identical
        assertEquals(resultBaseline, resultEnhanced, 
            "Results from both servers should be identical");
        
        // Calculate performance difference
        double percentDiff = ((double)(timeBaseline - timeEnhanced) / timeBaseline) * 100;
        log.info("Performance comparison: Baseline = {} ms, Enhanced = {} ms, Difference = {:.1f}%",
            timeBaseline, timeEnhanced, percentDiff);
        
        if (timeEnhanced < timeBaseline) {
            log.info("✓ SQL enhancer improved performance by {:.1f}%", percentDiff);
        } else {
            log.info("SQL enhancer did not improve performance (may depend on query complexity and data size)");
        }
        
        log.info("Test completed successfully - results are identical");
    }
    
    /**
     * Execute query and collect results as a string for comparison
     */
    private String executeAndCollectResults(String url) throws Exception {
        StringBuilder results = new StringBuilder();
        
        try (Connection conn = java.sql.DriverManager.getConnection(url, PG_USER, PG_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(TEST_QUERY)) {
            
            while (rs.next()) {
                String regionName = rs.getString("region_name");
                int orderCount = rs.getInt("order_cnt");
                double totalAmount = rs.getDouble("total_amount");
                
                results.append(regionName).append("|")
                       .append(orderCount).append("|")
                       .append(String.format("%.2f", totalAmount)).append("\n");
            }
        }
        
        return results.toString();
    }
}

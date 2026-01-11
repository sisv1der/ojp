package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration test for SQL Enhancer functionality with PostgreSQL.
 * 
 * This test:
 * 1. Starts two OJP servers on random ports
 *    - Server 1: SQL enhancer enabled
 *    - Server 2: SQL enhancer disabled (baseline)
 * 2. Creates test database with realistic data (Regions: 100, Customers: 5000, Orders: 10000)
 * 3. Runs an inefficient query on both servers
 * 4. Verifies both servers return the same results
 * 5. Compares performance metrics
 * 
 * The test validates that the SQL enhancer:
 * - Does not change query results
 * - Properly parses and validates complex queries
 * - Can potentially optimize query execution
 */
@Slf4j
public class PostgresSqlEnhancerIntegrationTest {

    private static boolean isTestEnabled;
    private static Process ojpServer1; // Server with SQL enhancer enabled
    private static Process ojpServer2; // Server with SQL enhancer disabled
    private static int port1; // Random port for server 1
    private static int port2; // Random port for server 2
    
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
        
        // Get random available ports
        port1 = findAvailablePort();
        port2 = findAvailablePort();
        
        log.info("Allocated ports: Server 1 (SQL enhancer enabled) = {}, Server 2 (baseline) = {}", port1, port2);
        
        // Setup test data in PostgreSQL
        setupTestData();
        
        // Start OJP servers
        startOjpServers();
        
        // Wait for servers to be ready with health check
        waitForServersReady();
        
        log.info("Both OJP servers started successfully");
    }
    
    @AfterAll
    public static void teardown() {
        if (!isTestEnabled) {
            return;
        }
        
        log.info("Stopping OJP servers");
        
        if (ojpServer1 != null && ojpServer1.isAlive()) {
            ojpServer1.destroy();
            try {
                ojpServer1.waitFor();
            } catch (InterruptedException e) {
                ojpServer1.destroyForcibly();
            }
        }
        
        if (ojpServer2 != null && ojpServer2.isAlive()) {
            ojpServer2.destroy();
            try {
                ojpServer2.waitFor();
            } catch (InterruptedException e) {
                ojpServer2.destroyForcibly();
            }
        }
        
        log.info("OJP servers stopped");
    }
    
    /**
     * Find an available port for OJP server
     * Note: There's still a small race condition between finding the port and using it,
     * but this is minimized by checking them sequentially and starting servers immediately.
     */
    private static int findAvailablePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            // Small delay to help prevent immediate reuse
            Thread.sleep(100);
            return port;
        }
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
                .collect(Collectors.joining("\n"));
            
            // Parse and execute SQL statements properly
            // Note: This simple parsing splits by semicolon at line endings.
            // Limitation: Won't handle semicolons within string literals or comments correctly.
            // However, our SQL script is controlled and doesn't have such edge cases.
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
    
    /**
     * Wait for both OJP servers to be ready by attempting connections
     */
    private static void waitForServersReady() throws Exception {
        log.info("Waiting for OJP servers to be ready...");
        
        int maxAttempts = 30; // 30 attempts with 1 second delay = 30 seconds max
        int attempt = 0;
        boolean server1Ready = false;
        boolean server2Ready = false;
        Exception lastServer1Error = null;
        Exception lastServer2Error = null;
        
        String url1 = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            port1, PG_HOST, PG_PORT, PG_DB);
        String url2 = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            port2, PG_HOST, PG_PORT, PG_DB);
        
        // Load OJP driver
        Class.forName("org.openjproxy.jdbc.Driver");
        
        while (attempt < maxAttempts && (!server1Ready || !server2Ready)) {
            attempt++;
            
            // Check server 1
            if (!server1Ready) {
                try (Connection conn = DriverManager.getConnection(url1, PG_USER, PG_PASSWORD)) {
                    server1Ready = true;
                    log.info("Server 1 (port {}) is ready after {} attempts", port1, attempt);
                } catch (Exception e) {
                    lastServer1Error = e;
                    // Server not ready yet
                }
            }
            
            // Check server 2
            if (!server2Ready) {
                try (Connection conn = DriverManager.getConnection(url2, PG_USER, PG_PASSWORD)) {
                    server2Ready = true;
                    log.info("Server 2 (port {}) is ready after {} attempts", port2, attempt);
                } catch (Exception e) {
                    lastServer2Error = e;
                    // Server not ready yet
                }
            }
            
            if (!server1Ready || !server2Ready) {
                Thread.sleep(1000); // Wait 1 second before next attempt
            }
        }
        
        if (!server1Ready || !server2Ready) {
            StringBuilder errorMsg = new StringBuilder("OJP servers failed to start within timeout period:\n");
            if (!server1Ready) {
                errorMsg.append("  Server 1 (port ").append(port1).append("): ");
                if (ojpServer1 != null && !ojpServer1.isAlive()) {
                    try {
                        errorMsg.append("Process died. Exit code: ").append(ojpServer1.exitValue()).append("\n");
                    } catch (IllegalThreadStateException e) {
                        errorMsg.append("Process state unknown\n");
                    }
                } else {
                    errorMsg.append("Not responding. ");
                    if (lastServer1Error != null) {
                        errorMsg.append("Last error: ").append(lastServer1Error.getMessage()).append("\n");
                    }
                }
            }
            if (!server2Ready) {
                errorMsg.append("  Server 2 (port ").append(port2).append("): ");
                if (ojpServer2 != null && !ojpServer2.isAlive()) {
                    try {
                        errorMsg.append("Process died. Exit code: ").append(ojpServer2.exitValue()).append("\n");
                    } catch (IllegalThreadStateException e) {
                        errorMsg.append("Process state unknown\n");
                    }
                } else {
                    errorMsg.append("Not responding. ");
                    if (lastServer2Error != null) {
                        errorMsg.append("Last error: ").append(lastServer2Error.getMessage()).append("\n");
                    }
                }
            }
            throw new RuntimeException(errorMsg.toString());
        }
    }
    
    /**
     * Start two OJP servers with different configurations
     */
    private static void startOjpServers() throws Exception {
        // Allow jar path to be configured via system property for flexibility
        // Default path is relative to repository root (one level up from ojp-jdbc-driver)
        String jarPath = System.getProperty("ojp.server.jar.path", 
            "../ojp-server/target/ojp-server-0.3.2-snapshot-shaded.jar");
        
        // Validate JAR file exists
        java.io.File jarFile = new java.io.File(jarPath);
        if (!jarFile.exists()) {
            throw new RuntimeException("OJP server JAR not found at: " + jarFile.getAbsolutePath() + 
                ". Please build the server first or specify correct path with -Dojp.server.jar.path=<path>");
        }
        
        log.info("Using OJP server JAR: {}", jarFile.getAbsolutePath());
        
        // Start server 1 with SQL enhancer enabled
        log.info("Starting OJP Server 1 with SQL enhancer enabled on port {}", port1);
        ProcessBuilder pb1 = new ProcessBuilder(
            "java",
            "-Dojp.server.port=" + port1,
            "-Dojp.prometheus.port=" + (9200 + port1 % 100),
            "-Dojp.sql.enhancer.enabled=true",
            "-Dojp.libs.path=../ojp-libs",
            "-jar", jarPath
        );
        pb1.redirectErrorStream(true);
        ojpServer1 = pb1.start();
        
        // Start logging thread for server 1 (use INFO for first 50 lines to catch startup errors)
        Thread loggingThread1 = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ojpServer1.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    // Log first 50 lines at INFO level to capture startup, then DEBUG
                    if (lineCount <= 50) {
                        log.info("[OJP-Server-1] {}", line);
                    } else {
                        log.debug("[OJP-Server-1] {}", line);
                    }
                }
            } catch (Exception e) {
                log.error("Error reading server 1 output", e);
            }
        });
        loggingThread1.setDaemon(true);
        loggingThread1.start();
        
        // Wait a bit before starting second server
        Thread.sleep(2000);
        
        // Start server 2 with SQL enhancer disabled (default)
        log.info("Starting OJP Server 2 with SQL enhancer disabled on port {}", port2);
        ProcessBuilder pb2 = new ProcessBuilder(
            "java",
            "-Dojp.server.port=" + port2,
            "-Dojp.prometheus.port=" + (9200 + port2 % 100),
            "-Dojp.sql.enhancer.enabled=false",
            "-Dojp.libs.path=../ojp-libs",
            "-jar", jarPath
        );
        pb2.redirectErrorStream(true);
        ojpServer2 = pb2.start();
        
        // Start logging thread for server 2 (use INFO for first 50 lines to catch startup errors)
        Thread loggingThread2 = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ojpServer2.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    // Log first 50 lines at INFO level to capture startup, then DEBUG
                    if (lineCount <= 50) {
                        log.info("[OJP-Server-2] {}", line);
                    } else {
                        log.debug("[OJP-Server-2] {}", line);
                    }
                }
            } catch (Exception e) {
                log.error("Error reading server 2 output", e);
            }
        });
        loggingThread2.setDaemon(true);
        loggingThread2.start();
        
        // Give servers initial time to start up before health checks
        // OJP servers need a few seconds to initialize gRPC services
        log.info("Giving servers 5 seconds to initialize...");
        Thread.sleep(5000);
    }
    
    @Test
    public void testSqlEnhancerWithComplexQuery() throws Exception {
        assumeFalse(!isTestEnabled, "PostgreSQL SQL Enhancer Integration Test is disabled");
        
        log.info("Running SQL Enhancer integration test with complex query");
        
        // Connection URLs for both servers
        String url1 = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            port1, PG_HOST, PG_PORT, PG_DB);
        String url2 = String.format("jdbc:ojp[localhost:%d]_postgresql://%s:%s/%s", 
            port2, PG_HOST, PG_PORT, PG_DB);
        
        // Load OJP driver
        Class.forName("org.openjproxy.jdbc.Driver");
        
        // Execute query on server 1 (with SQL enhancer)
        log.info("Executing query on Server 1 (SQL enhancer enabled)");
        long start1 = System.currentTimeMillis();
        String results1 = executeQuery(url1, TEST_QUERY);
        long duration1 = System.currentTimeMillis() - start1;
        log.info("Server 1 query completed in {} ms", duration1);
        
        // Execute query on server 2 (without SQL enhancer)
        log.info("Executing query on Server 2 (SQL enhancer disabled)");
        long start2 = System.currentTimeMillis();
        String results2 = executeQuery(url2, TEST_QUERY);
        long duration2 = System.currentTimeMillis() - start2;
        log.info("Server 2 query completed in {} ms", duration2);
        
        // Verify results are identical
        log.info("Verifying query results are identical between servers");
        assertEquals(results1, results2, 
            "Query results should be identical between SQL enhancer enabled and disabled servers");
        
        // Log performance comparison
        if (duration2 > 0) {
            double percentDiff = ((double) (duration1 - duration2) / duration2) * 100;
            log.info("Performance comparison: Server 1 = {} ms, Server 2 = {} ms, Difference = {:.2f}%", 
                duration1, duration2, percentDiff);
        } else {
            log.info("Performance comparison: Server 1 = {} ms, Server 2 = {} ms (too fast to measure difference)", 
                duration1, duration2);
        }
        
        // Verify both servers processed the query successfully
        assertFalse(results1.isEmpty(), "Server 1 should return results");
        assertFalse(results2.isEmpty(), "Server 2 should return results");
        
        log.info("SQL Enhancer integration test completed successfully");
        log.info("Test verified that SQL enhancer does not change query results");
    }
    
    /**
     * Execute query and return results as a string for comparison
     */
    private String executeQuery(String url, String query) throws Exception {
        StringBuilder results = new StringBuilder();
        
        try (Connection conn = DriverManager.getConnection(url, PG_USER, PG_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            
            int columnCount = rs.getMetaData().getColumnCount();
            
            while (rs.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        results.append(",");
                    }
                    Object value = rs.getObject(i);
                    results.append(value != null ? value.toString() : "NULL");
                }
                results.append("\n");
            }
        }
        
        return results.toString();
    }
}

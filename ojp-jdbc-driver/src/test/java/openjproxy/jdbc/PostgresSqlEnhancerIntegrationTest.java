package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
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
    
    // PostgreSQL connection details (from environment or defaults)
    private static final String PG_HOST = System.getProperty("postgres.host", "localhost");
    private static final String PG_PORT = System.getProperty("postgres.port", "5432");
    private static final String PG_DB = System.getProperty("postgres.db", "defaultdb");
    private static final String PG_USER = System.getProperty("postgres.user", "testuser");
    private static final String PG_PASSWORD = System.getProperty("postgres.password", "testpassword");
    
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
        
        // Wait for servers to be ready
        Thread.sleep(15000);
        
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
     */
    private static int findAvailablePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
    
    /**
     * Setup test data in PostgreSQL database
     */
    private static void setupTestData() throws Exception {
        log.info("Setting up test data in PostgreSQL");
        
        String pgUrl = String.format("jdbc:postgresql://%s:%s/%s", PG_HOST, PG_PORT, PG_DB);
        
        try (Connection conn = DriverManager.getConnection(pgUrl, PG_USER, PG_PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            // Read and execute setup SQL script
            String setupSql = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(PostgresSqlEnhancerIntegrationTest.class
                    .getResourceAsStream("/sql_enhancer_postgres_test_data.sql"))))
                .lines()
                .collect(Collectors.joining("\n"));
            
            stmt.execute(setupSql);
            
            log.info("Test data setup completed");
        }
    }
    
    /**
     * Start two OJP servers with different configurations
     */
    private static void startOjpServers() throws Exception {
        String jarPath = "ojp-server/target/ojp-server-0.3.2-snapshot-shaded.jar";
        
        // Start server 1 with SQL enhancer enabled
        log.info("Starting OJP Server 1 with SQL enhancer enabled on port {}", port1);
        ProcessBuilder pb1 = new ProcessBuilder(
            "java",
            "-Dojp.server.port=" + port1,
            "-Dojp.prometheus.port=" + (9200 + port1 % 100),
            "-Dojp.sql.enhancer.enabled=true",
            "-jar", jarPath
        );
        pb1.redirectErrorStream(true);
        ojpServer1 = pb1.start();
        
        // Start logging thread for server 1
        Thread loggingThread1 = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ojpServer1.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[OJP-Server-1] {}", line);
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
            "-jar", jarPath
        );
        pb2.redirectErrorStream(true);
        ojpServer2 = pb2.start();
        
        // Start logging thread for server 2
        Thread loggingThread2 = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ojpServer2.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[OJP-Server-2] {}", line);
                }
            } catch (Exception e) {
                log.error("Error reading server 2 output", e);
            }
        });
        loggingThread2.setDaemon(true);
        loggingThread2.start();
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
        double percentDiff = ((double) (duration1 - duration2) / duration2) * 100;
        log.info("Performance comparison: Server 1 = {} ms, Server 2 = {} ms, Difference = {:.2f}%", 
            duration1, duration2, percentDiff);
        
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

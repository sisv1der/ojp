package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OjpSystemPropertiesBridgeTest {

    private static final String PROP_MAX_POOL_SIZE = "ojp.connection.pool.maximumPoolSize";
    private static final String PROP_MIN_IDLE = "ojp.connection.pool.minimumIdle";
    private static final String PROP_CONN_TIMEOUT = "ojp.connection.pool.connectionTimeout";
    private static final String PROP_IDLE_TIMEOUT = "ojp.connection.pool.idleTimeout";
    private static final String PROP_MAX_LIFETIME = "ojp.connection.pool.maxLifetime";
    private static final String PROP_MAX_INBOUND = "ojp.grpc.maxInboundMessageSize";
    private static final String PROP_DATASOURCE_NAME = "ojp.datasource.name";
    private static final String PROP_ENVIRONMENT = "ojp.environment";

    private static final String DS_NAME = "myApp";
    private static final String PREFIXED_MAX_POOL_SIZE = DS_NAME + "." + PROP_MAX_POOL_SIZE;
    private static final String PREFIXED_MIN_IDLE = DS_NAME + "." + PROP_MIN_IDLE;
    private static final String PREFIXED_CONN_TIMEOUT = DS_NAME + "." + PROP_CONN_TIMEOUT;
    private static final String PREFIXED_IDLE_TIMEOUT = DS_NAME + "." + PROP_IDLE_TIMEOUT;
    private static final String PREFIXED_MAX_LIFETIME = DS_NAME + "." + PROP_MAX_LIFETIME;
    private static final String DEFAULT_PREFIXED_MAX_POOL_SIZE = "default." + PROP_MAX_POOL_SIZE;

    @BeforeEach
    void clearProperties() {
        clearTestProperties();
    }

    @AfterEach
    void restoreProperties() {
        clearTestProperties();
    }

    private void clearTestProperties() {
        System.clearProperty(PROP_MAX_POOL_SIZE);
        System.clearProperty(PROP_MIN_IDLE);
        System.clearProperty(PROP_CONN_TIMEOUT);
        System.clearProperty(PROP_IDLE_TIMEOUT);
        System.clearProperty(PROP_MAX_LIFETIME);
        System.clearProperty(PROP_MAX_INBOUND);
        System.clearProperty(PROP_DATASOURCE_NAME);
        System.clearProperty(PROP_ENVIRONMENT);
        System.clearProperty(PREFIXED_MAX_POOL_SIZE);
        System.clearProperty(PREFIXED_MIN_IDLE);
        System.clearProperty(PREFIXED_CONN_TIMEOUT);
        System.clearProperty(PREFIXED_IDLE_TIMEOUT);
        System.clearProperty(PREFIXED_MAX_LIFETIME);
        System.clearProperty(DEFAULT_PREFIXED_MAX_POOL_SIZE);
    }

    @Test
    void shouldPrefixPoolPropertiesWithDataSourceName() {
        OjpProperties props = new OjpProperties();
        props.setEnvironment("prod");

        OjpProperties.Datasource ds = new OjpProperties.Datasource();
        ds.setName(DS_NAME);
        props.setDatasource(ds);

        OjpProperties.Connection.Pool pool = new OjpProperties.Connection.Pool();
        pool.setMaximumPoolSize(20);
        pool.setMinimumIdle(5);
        pool.setConnectionTimeout(30000L);
        pool.setIdleTimeout(600000L);
        pool.setMaxLifetime(1800000L);

        OjpProperties.Connection conn = new OjpProperties.Connection();
        conn.setPool(pool);
        props.setConnection(conn);

        OjpProperties.Grpc grpc = new OjpProperties.Grpc();
        grpc.setMaxInboundMessageSize(16777216);
        props.setGrpc(grpc);

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        assertEquals("prod", System.getProperty(PROP_ENVIRONMENT));
        assertEquals(DS_NAME, System.getProperty(PROP_DATASOURCE_NAME));
        // gRPC is not pool-specific, no prefix
        assertEquals("16777216", System.getProperty(PROP_MAX_INBOUND));

        // Pool properties must use the datasource-name prefix
        assertEquals("20", System.getProperty(PREFIXED_MAX_POOL_SIZE));
        assertEquals("5", System.getProperty(PREFIXED_MIN_IDLE));
        assertEquals("30000", System.getProperty(PREFIXED_CONN_TIMEOUT));
        assertEquals("600000", System.getProperty(PREFIXED_IDLE_TIMEOUT));
        assertEquals("1800000", System.getProperty(PREFIXED_MAX_LIFETIME));

        // Unprefixed pool properties must NOT be set
        assertNull(System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MIN_IDLE));
    }

    @Test
    void shouldUseUnprefixedPoolPropertiesWhenNoDataSourceName() {
        OjpProperties props = new OjpProperties();

        OjpProperties.Connection.Pool pool = new OjpProperties.Connection.Pool();
        pool.setMaximumPoolSize(10);
        pool.setMinimumIdle(2);

        OjpProperties.Connection conn = new OjpProperties.Connection();
        conn.setPool(pool);
        props.setConnection(conn);

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        // No datasource name → unprefixed pool properties
        assertEquals("10", System.getProperty(PROP_MAX_POOL_SIZE));
        assertEquals("2", System.getProperty(PROP_MIN_IDLE));
        assertNull(System.getProperty(PREFIXED_MAX_POOL_SIZE));
    }

    @Test
    void shouldUseUnprefixedPoolPropertiesForDefaultDataSourceName() {
        OjpProperties props = new OjpProperties();

        OjpProperties.Datasource ds = new OjpProperties.Datasource();
        ds.setName("default");
        props.setDatasource(ds);

        OjpProperties.Connection.Pool pool = new OjpProperties.Connection.Pool();
        pool.setMaximumPoolSize(15);

        OjpProperties.Connection conn = new OjpProperties.Connection();
        conn.setPool(pool);
        props.setConnection(conn);

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        // "default" datasource → unprefixed pool properties
        assertEquals("15", System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(DEFAULT_PREFIXED_MAX_POOL_SIZE));
    }

    @Test
    void shouldNotSetPropertiesForNullValues() {
        OjpProperties props = new OjpProperties();
        // Leave all values null

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        assertNull(System.getProperty(PROP_ENVIRONMENT));
        assertNull(System.getProperty(PROP_DATASOURCE_NAME));
        assertNull(System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MAX_INBOUND));
    }

    @Test
    void shouldNotOverrideExistingSystemProperties() {
        System.setProperty(PREFIXED_MAX_POOL_SIZE, "50");

        OjpProperties props = new OjpProperties();

        OjpProperties.Datasource ds = new OjpProperties.Datasource();
        ds.setName(DS_NAME);
        props.setDatasource(ds);

        OjpProperties.Connection.Pool pool = new OjpProperties.Connection.Pool();
        pool.setMaximumPoolSize(10);

        OjpProperties.Connection conn = new OjpProperties.Connection();
        conn.setPool(pool);
        props.setConnection(conn);

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        // Existing system property should not be overwritten
        assertEquals("50", System.getProperty(PREFIXED_MAX_POOL_SIZE));
    }

    @Test
    void shouldOnlySetExplicitlyConfiguredProperties() {
        OjpProperties props = new OjpProperties();
        OjpProperties.Connection.Pool pool = new OjpProperties.Connection.Pool();
        pool.setMaximumPoolSize(25);
        // minimumIdle intentionally left null

        OjpProperties.Connection conn = new OjpProperties.Connection();
        conn.setPool(pool);
        props.setConnection(conn);

        OjpSystemPropertiesBridge bridge = new OjpSystemPropertiesBridge(props);
        bridge.applySystemProperties();

        assertEquals("25", System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MIN_IDLE));
    }
}

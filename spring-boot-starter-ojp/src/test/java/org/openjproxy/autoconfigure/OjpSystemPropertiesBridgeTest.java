package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OjpSystemPropertiesBridgeTest {

    private static final String PROP_MAX_POOL_SIZE = "ojp.connection.pool.maximumPoolSize";
    private static final String PROP_MIN_IDLE = "ojp.connection.pool.minimumIdle";
    private static final String PROP_CONN_TIMEOUT = "ojp.connection.pool.connectionTimeout";
    private static final String PROP_IDLE_TIMEOUT = "ojp.connection.pool.idleTimeout";
    private static final String PROP_MAX_LIFETIME = "ojp.connection.pool.maxLifetime";
    private static final String PROP_MAX_INBOUND = "ojp.grpc.maxInboundMessageSize";

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
    }

    @Test
    void shouldSetAllPoolPropertiesFromEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "20");
        env.setProperty("ojp.connection.pool.minimum-idle", "5");
        env.setProperty("ojp.connection.pool.connection-timeout", "30000");
        env.setProperty("ojp.connection.pool.idle-timeout", "600000");
        env.setProperty("ojp.connection.pool.max-lifetime", "1800000");
        env.setProperty("ojp.grpc.max-inbound-message-size", "16777216");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("20", System.getProperty(PROP_MAX_POOL_SIZE));
        assertEquals("5", System.getProperty(PROP_MIN_IDLE));
        assertEquals("30000", System.getProperty(PROP_CONN_TIMEOUT));
        assertEquals("600000", System.getProperty(PROP_IDLE_TIMEOUT));
        assertEquals("1800000", System.getProperty(PROP_MAX_LIFETIME));
        assertEquals("16777216", System.getProperty(PROP_MAX_INBOUND));
    }

    @Test
    void shouldForwardAlreadyCamelCaseKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximumPoolSize", "25");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("25", System.getProperty(PROP_MAX_POOL_SIZE));
    }

    @Test
    void shouldNotSetPropertiesWhenNoOjpPropertiesPresent() {
        MockEnvironment env = new MockEnvironment();
        // No ojp.* properties set

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertNull(System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MAX_INBOUND));
    }

    @Test
    void shouldNotOverrideExistingSystemProperties() {
        System.setProperty(PROP_MAX_POOL_SIZE, "50");

        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "10");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        // Existing system property should not be overwritten
        assertEquals("50", System.getProperty(PROP_MAX_POOL_SIZE));
    }

    @Test
    void shouldOnlySetExplicitlyConfiguredProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "25");
        // minimumIdle intentionally not set

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("25", System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MIN_IDLE));
    }

    @Test
    void kebabToCamelCaseShouldConvertCorrectly() {
        assertEquals("ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.maximum-pool-size"));
        assertEquals("ojp.grpc.maxInboundMessageSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.grpc.max-inbound-message-size"));
        assertEquals("ojp.connection.pool.minimumIdle",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.minimum-idle"));
    }

    @Test
    void kebabToCamelCaseShouldLeaveAlreadyCamelCaseUnchanged() {
        assertEquals("ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.maximumPoolSize"));
    }
}

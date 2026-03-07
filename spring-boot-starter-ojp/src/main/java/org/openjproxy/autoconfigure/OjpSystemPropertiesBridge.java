package org.openjproxy.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges OJP configuration from Spring Boot's {@code application.properties}
 * to JVM system properties.
 *
 * <p>The OJP JDBC driver's {@code DatasourcePropertiesLoader} reads pool and gRPC
 * settings from three sources (in descending priority): environment variables,
 * system properties, and {@code ojp.properties} file. This bridge sets system
 * properties from the {@link OjpProperties} bean so that {@code ojp.*} settings
 * declared in {@code application.properties} are transparently forwarded to the
 * driver without requiring a separate {@code ojp.properties} file.</p>
 *
 * <p>Only non-null property values are written as system properties, preserving
 * any existing system-property or environment-variable overrides.</p>
 *
 * <p>Property name mapping (Spring Boot → system property):</p>
 * <ul>
 *   <li>{@code ojp.connection.pool.maximum-pool-size} → {@code ojp.connection.pool.maximumPoolSize}
 *       (or {@code {name}.ojp.connection.pool.maximumPoolSize} when a non-default datasource name is set)</li>
 *   <li>{@code ojp.connection.pool.minimum-idle}      → {@code ojp.connection.pool.minimumIdle}</li>
 *   <li>{@code ojp.connection.pool.connection-timeout} → {@code ojp.connection.pool.connectionTimeout}</li>
 *   <li>{@code ojp.connection.pool.idle-timeout}      → {@code ojp.connection.pool.idleTimeout}</li>
 *   <li>{@code ojp.connection.pool.max-lifetime}      → {@code ojp.connection.pool.maxLifetime}</li>
 *   <li>{@code ojp.grpc.max-inbound-message-size}     → {@code ojp.grpc.maxInboundMessageSize}</li>
 *   <li>{@code ojp.datasource.name}                   → {@code ojp.datasource.name}</li>
 *   <li>{@code ojp.environment}                       → {@code ojp.environment}</li>
 * </ul>
 *
 * <p>When {@code ojp.datasource.name} is set to a non-default value (e.g., {@code myApp}),
 * connection pool properties are forwarded with the datasource name prefix so that
 * {@code DatasourcePropertiesLoader} can look them up per-datasource:
 * {@code myApp.ojp.connection.pool.maximumPoolSize=20}.</p>
 */
public class OjpSystemPropertiesBridge {

    private static final Logger log = LoggerFactory.getLogger(OjpSystemPropertiesBridge.class);

    private final OjpProperties ojpProperties;

    public OjpSystemPropertiesBridge(OjpProperties ojpProperties) {
        this.ojpProperties = ojpProperties;
    }

    /**
     * Sets OJP system properties after the bean is constructed.
     * Only properties explicitly set in {@code application.properties} (non-null values)
     * are written, so they do not override existing system properties or environment variables.
     *
     * <p>When a non-default datasource name is configured, pool properties are set with
     * the {@code {name}.ojp.connection.pool.*} prefix so that {@code DatasourcePropertiesLoader}
     * resolves them correctly for the named datasource.</p>
     */
    @PostConstruct
    public void applySystemProperties() {
        setIfAbsent("ojp.environment", ojpProperties.getEnvironment());

        String datasourceName = null;
        if (ojpProperties.getDatasource() != null) {
            datasourceName = ojpProperties.getDatasource().getName();
            setIfAbsent("ojp.datasource.name", datasourceName);
        }

        // Determine pool property prefix:
        // - named (non-default) datasource → "{name}.ojp.connection.pool."
        // - default or no name             → "ojp.connection.pool."
        String poolPrefix = determinePoolPrefix(datasourceName);

        if (ojpProperties.getConnection() != null && ojpProperties.getConnection().getPool() != null) {
            OjpProperties.Connection.Pool pool = ojpProperties.getConnection().getPool();
            setIfAbsent(poolPrefix + "maximumPoolSize",
                    pool.getMaximumPoolSize() != null ? pool.getMaximumPoolSize().toString() : null);
            setIfAbsent(poolPrefix + "minimumIdle",
                    pool.getMinimumIdle() != null ? pool.getMinimumIdle().toString() : null);
            setIfAbsent(poolPrefix + "connectionTimeout",
                    pool.getConnectionTimeout() != null ? pool.getConnectionTimeout().toString() : null);
            setIfAbsent(poolPrefix + "idleTimeout",
                    pool.getIdleTimeout() != null ? pool.getIdleTimeout().toString() : null);
            setIfAbsent(poolPrefix + "maxLifetime",
                    pool.getMaxLifetime() != null ? pool.getMaxLifetime().toString() : null);
        }

        if (ojpProperties.getGrpc() != null) {
            setIfAbsent("ojp.grpc.maxInboundMessageSize",
                    ojpProperties.getGrpc().getMaxInboundMessageSize() != null
                            ? ojpProperties.getGrpc().getMaxInboundMessageSize().toString() : null);
        }
    }

    /**
     * Determines the system property prefix for pool settings.
     *
     * <p>When a non-default datasource name is configured, pool properties are prefixed
     * with {@code {name}.} so that {@code DatasourcePropertiesLoader} resolves them for
     * the correct named datasource. For the default datasource (or when no name is set),
     * the standard {@code ojp.connection.pool.} prefix is used.</p>
     *
     * @param datasourceName the configured datasource name, or {@code null} if not set
     * @return the pool property prefix including trailing dot
     */
    private String determinePoolPrefix(String datasourceName) {
        if (datasourceName != null && !datasourceName.isEmpty() && !"default".equals(datasourceName)) {
            return datasourceName + ".ojp.connection.pool.";
        }
        return "ojp.connection.pool.";
    }

    /**
     * Sets a system property only if the given value is non-null and the property
     * is not already set (preserving existing system-property or env-var overrides).
     *
     * @param key   system property key
     * @param value value to set, or {@code null} to skip
     */
    private void setIfAbsent(String key, String value) {
        if (value == null) {
            return;
        }
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            log.debug("Set OJP system property: {}={}", key, value);
        } else {
            log.debug("Skipped OJP system property (already set): {}", key);
        }
    }
}

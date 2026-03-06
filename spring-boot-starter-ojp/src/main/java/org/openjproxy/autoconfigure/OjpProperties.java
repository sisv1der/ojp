package org.openjproxy.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for OJP (Open J Proxy) Spring Boot integration.
 *
 * <p>These properties configure the OJP JDBC driver behaviour and the server-side
 * connection pool managed by the OJP proxy server. They are mapped from the
 * {@code ojp.*} prefix in {@code application.properties} (or {@code application.yaml})
 * and bridge to the {@code ojp.properties} file that OJP's driver reads natively.</p>
 *
 * <p>Example {@code application.properties}:</p>
 * <pre>
 * # OJP connection pool settings (sent to the OJP server)
 * ojp.connection.pool.maximum-pool-size=20
 * ojp.connection.pool.minimum-idle=5
 * ojp.connection.pool.connection-timeout=30000
 *
 * # OJP gRPC settings
 * ojp.grpc.max-inbound-message-size=16777216
 *
 * # OJP datasource name (maps to the (dataSourceName) in the JDBC URL)
 * ojp.datasource.name=myApp
 *
 * # OJP environment profile (loads ojp-{environment}.properties first)
 * ojp.environment=prod
 * </pre>
 *
 * <p>These properties are automatically propagated as system properties so that
 * {@code DatasourcePropertiesLoader} can pick them up with the highest precedence.</p>
 */
@ConfigurationProperties(prefix = "ojp")
public class OjpProperties {

    /**
     * OJP environment profile name.
     * When set, OJP will attempt to load {@code ojp-{environment}.properties}
     * before falling back to {@code ojp.properties}.
     */
    private String environment;

    /**
     * OJP datasource name.
     * Identifies which named datasource configuration to use on the OJP server.
     * This corresponds to the {@code (dataSourceName)} segment in the OJP JDBC URL.
     */
    private Datasource datasource = new Datasource();

    /**
     * OJP server-side connection pool settings.
     * These are forwarded to the OJP proxy server to configure HikariCP.
     */
    private Connection connection = new Connection();

    /**
     * OJP gRPC transport settings.
     */
    private Grpc grpc = new Grpc();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public void setDatasource(Datasource datasource) {
        this.datasource = datasource;
    }

    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    public Grpc getGrpc() {
        return grpc;
    }

    public void setGrpc(Grpc grpc) {
        this.grpc = grpc;
    }

    /**
     * OJP datasource identification settings.
     */
    public static class Datasource {

        /**
         * Named datasource identifier used for per-datasource pool configuration
         * on the OJP server. Corresponds to the {@code (dataSourceName)} in the JDBC URL.
         */
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * OJP connection pool settings forwarded to the OJP proxy server.
     */
    public static class Connection {

        private Pool pool = new Pool();

        public Pool getPool() {
            return pool;
        }

        public void setPool(Pool pool) {
            this.pool = pool;
        }

        /**
         * Server-side HikariCP pool size and timeout settings.
         */
        public static class Pool {

            /**
             * Maximum number of connections the OJP server will maintain in the pool.
             * Maps to {@code ojp.connection.pool.maximumPoolSize}.
             */
            private Integer maximumPoolSize;

            /**
             * Minimum number of idle connections the OJP server will keep in the pool.
             * Maps to {@code ojp.connection.pool.minimumIdle}.
             */
            private Integer minimumIdle;

            /**
             * Maximum time in milliseconds to wait for a connection from the pool.
             * Maps to {@code ojp.connection.pool.connectionTimeout}.
             */
            private Long connectionTimeout;

            /**
             * Time in milliseconds after which idle connections are eligible for removal.
             * Maps to {@code ojp.connection.pool.idleTimeout}.
             */
            private Long idleTimeout;

            /**
             * Maximum lifetime of a connection in milliseconds.
             * Maps to {@code ojp.connection.pool.maxLifetime}.
             */
            private Long maxLifetime;

            public Integer getMaximumPoolSize() {
                return maximumPoolSize;
            }

            public void setMaximumPoolSize(Integer maximumPoolSize) {
                this.maximumPoolSize = maximumPoolSize;
            }

            public Integer getMinimumIdle() {
                return minimumIdle;
            }

            public void setMinimumIdle(Integer minimumIdle) {
                this.minimumIdle = minimumIdle;
            }

            public Long getConnectionTimeout() {
                return connectionTimeout;
            }

            public void setConnectionTimeout(Long connectionTimeout) {
                this.connectionTimeout = connectionTimeout;
            }

            public Long getIdleTimeout() {
                return idleTimeout;
            }

            public void setIdleTimeout(Long idleTimeout) {
                this.idleTimeout = idleTimeout;
            }

            public Long getMaxLifetime() {
                return maxLifetime;
            }

            public void setMaxLifetime(Long maxLifetime) {
                this.maxLifetime = maxLifetime;
            }
        }
    }

    /**
     * OJP gRPC transport settings.
     */
    public static class Grpc {

        /**
         * Maximum inbound message size in bytes for gRPC communication.
         * Increase this value when working with large LOB data.
         * Maps to {@code ojp.grpc.maxInboundMessageSize}.
         */
        private Integer maxInboundMessageSize;

        public Integer getMaxInboundMessageSize() {
            return maxInboundMessageSize;
        }

        public void setMaxInboundMessageSize(Integer maxInboundMessageSize) {
            this.maxInboundMessageSize = maxInboundMessageSize;
        }
    }
}

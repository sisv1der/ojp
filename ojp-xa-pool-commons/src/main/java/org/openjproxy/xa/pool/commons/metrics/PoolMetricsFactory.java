package org.openjproxy.xa.pool.commons.metrics;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Factory for creating PoolMetrics implementations.
 * <p>
 * This factory determines which metrics implementation to use based on:
 * <ul>
 *   <li>Configuration settings (xa.metrics.enabled)</li>
 *   <li>Availability of OpenTelemetry on the classpath</li>
 *   <li>Provided OpenTelemetry instance</li>
 * </ul>
 * </p>
 */
public class PoolMetricsFactory {
    private static final Logger log = LoggerFactory.getLogger(PoolMetricsFactory.class);
    
    private static final String METRICS_ENABLED_KEY = "xa.metrics.enabled";
    private static final String POOL_NAME_KEY = "xa.poolName";
    private static final String DEFAULT_POOL_NAME = "ojp-xa-pool";
    
    /**
     * Creates a PoolMetrics instance based on configuration and OpenTelemetry availability.
     *
     * @param config the pool configuration
     * @param openTelemetry the OpenTelemetry instance (can be null)
     * @return a PoolMetrics implementation
     */
    public static PoolMetrics create(Map<String, String> config, OpenTelemetry openTelemetry) {
        if (config == null) {
            log.warn("Configuration is null, metrics disabled");
            return NoOpPoolMetrics.INSTANCE;
        }
        
        // Check if metrics are explicitly disabled
        String metricsEnabled = config.get(METRICS_ENABLED_KEY);
        if (metricsEnabled != null && "false".equalsIgnoreCase(metricsEnabled.trim())) {
            log.info("Metrics explicitly disabled via configuration");
            return NoOpPoolMetrics.INSTANCE;
        }
        
        // Try to create OpenTelemetry metrics if available
        if (openTelemetry != null) {
            try {
                String poolName = config.getOrDefault(POOL_NAME_KEY, DEFAULT_POOL_NAME);
                log.info("Creating OpenTelemetry metrics for pool: {}", poolName);
                return new OpenTelemetryPoolMetrics(openTelemetry, poolName);
            } catch (Exception e) {
                log.warn("Failed to create OpenTelemetry metrics, falling back to no-op: {}", 
                        e.getMessage());
                return NoOpPoolMetrics.INSTANCE;
            }
        }
        
        // Check if OpenTelemetry is available on classpath
        if (isOpenTelemetryAvailable()) {
            log.info("OpenTelemetry is available but no instance provided, metrics will not be collected");
        } else {
            log.debug("OpenTelemetry not available on classpath, metrics disabled");
        }
        
        return NoOpPoolMetrics.INSTANCE;
    }
    
    /**
     * Checks if OpenTelemetry is available on the classpath.
     *
     * @return true if OpenTelemetry is available, false otherwise
     */
    private static boolean isOpenTelemetryAvailable() {
        try {
            Class.forName("io.opentelemetry.api.OpenTelemetry");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

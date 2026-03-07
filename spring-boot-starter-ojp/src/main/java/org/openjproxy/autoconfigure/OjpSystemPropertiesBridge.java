package org.openjproxy.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridges OJP configuration from Spring Boot's {@code application.properties}
 * to JVM system properties.
 *
 * <p>The OJP JDBC driver's {@code DatasourcePropertiesLoader} reads pool and gRPC
 * settings from three sources (in descending priority): environment variables,
 * system properties, and {@code ojp.properties} file. This bridge iterates all
 * {@code ojp.*} properties in Spring's {@link Environment} and forwards them to JVM
 * system properties so that settings declared in {@code application.properties} are
 * transparently available to the driver without a separate {@code ojp.properties}
 * file. Any new OJP property is picked up automatically without code changes.</p>
 *
 * <p>Property keys written in Spring's kebab-case format are automatically
 * converted to camelCase before being set as system properties:</p>
 * <pre>
 *   ojp.connection.pool.maximum-pool-size  →  ojp.connection.pool.maximumPoolSize
 *   ojp.grpc.max-inbound-message-size      →  ojp.grpc.maxInboundMessageSize
 * </pre>
 *
 * <p>Only non-null property values are written as system properties, and existing
 * system-property or environment-variable values are never overridden.</p>
 *
 * <p>The datasource name is embedded in the OJP JDBC URL using parentheses notation:
 * {@code jdbc:ojp[localhost:1059(myApp)]_...}. The OJP driver extracts this name
 * automatically when establishing a connection.</p>
 */
public class OjpSystemPropertiesBridge {

    private static final Logger log = LoggerFactory.getLogger(OjpSystemPropertiesBridge.class);
    private static final String OJP_PREFIX = "ojp.";

    private final Environment environment;

    public OjpSystemPropertiesBridge(Environment environment) {
        this.environment = environment;
    }

    /**
     * Iterates all {@code ojp.*} keys in the Spring {@link Environment}, converts
     * kebab-case names to camelCase, and sets them as JVM system properties.
     * Only non-null values are written, and existing system properties are preserved.
     */
    @PostConstruct
    public void applySystemProperties() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            log.warn("Environment is not a ConfigurableEnvironment; OJP system properties will not be applied.");
            return;
        }
        Set<String> processed = new HashSet<>();
        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(OJP_PREFIX) && processed.add(name)) {
                        String value = environment.getProperty(name);
                        if (value != null) {
                            setIfAbsent(kebabToCamelCase(name), value);
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts a kebab-case property key to camelCase.
     * For example, {@code ojp.connection.pool.maximum-pool-size} becomes
     * {@code ojp.connection.pool.maximumPoolSize}.
     *
     * @param key the property key, potentially containing hyphens
     * @return the same key with each {@code -x} sequence replaced by {@code X}
     */
    static String kebabToCamelCase(String key) {
        StringBuilder result = new StringBuilder(key.length());
        boolean capitalize = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                capitalize = true;
            } else {
                result.append(capitalize ? Character.toUpperCase(c) : c);
                capitalize = false;
            }
        }
        return result.toString();
    }

    private void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            log.debug("Set OJP system property: {}={}", key, value);
        } else {
            log.debug("Skipped OJP system property (already set): {}", key);
        }
    }
}

package org.openjproxy.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot {@link EnvironmentPostProcessor} that automatically configures sensible
 * defaults when an OJP JDBC URL is detected.
 *
 * <p>When {@code spring.datasource.url} starts with {@code jdbc:ojp}, this post-processor
 * injects the following defaults (only when not already explicitly set by the user):</p>
 * <ul>
 *   <li>{@code spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver}</li>
 *   <li>{@code spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource}</li>
 * </ul>
 *
 * <p>Setting {@code spring.datasource.type} to {@code SimpleDriverDataSource} prevents
 * Spring Boot from wrapping the OJP connection in a local HikariCP pool. OJP manages
 * connection pooling centrally on the proxy server, so a local pool is unnecessary and
 * would consume extra resources.</p>
 *
 * <p>This processor is registered via {@code META-INF/spring.factories} under the
 * {@code org.springframework.boot.env.EnvironmentPostProcessor} key, which is supported
 * across Spring Boot 3.x and 4.x.</p>
 *
 * <p>No action is taken when the URL is not an OJP URL, making this safe to include
 * in projects that conditionally use OJP.</p>
 */
public class OjpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OjpEnvironmentPostProcessor.class);

    static final String OJP_DATASOURCE_DEFAULTS = "ojpDataSourceDefaults";
    static final String OJP_URL_PREFIX = "jdbc:ojp";
    static final String DRIVER_CLASS_NAME = "spring.datasource.driver-class-name";
    static final String DATASOURCE_TYPE = "spring.datasource.type";
    static final String OJP_DRIVER_CLASS = "org.openjproxy.jdbc.Driver";
    static final String SIMPLE_DRIVER_DATASOURCE = "org.springframework.jdbc.datasource.SimpleDriverDataSource";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(OJP_URL_PREFIX)) {
            return;
        }

        log.debug("OJP JDBC URL detected: {}. Applying OJP datasource defaults.", url);

        Map<String, Object> defaults = new LinkedHashMap<>();

        if (!environment.containsProperty(DRIVER_CLASS_NAME)) {
            defaults.put(DRIVER_CLASS_NAME, OJP_DRIVER_CLASS);
            log.debug("Setting default: {}={}", DRIVER_CLASS_NAME, OJP_DRIVER_CLASS);
        }

        if (!environment.containsProperty(DATASOURCE_TYPE)) {
            defaults.put(DATASOURCE_TYPE, SIMPLE_DRIVER_DATASOURCE);
            log.debug("Setting default: {}={}", DATASOURCE_TYPE, SIMPLE_DRIVER_DATASOURCE);
        }

        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(OJP_DATASOURCE_DEFAULTS, defaults));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}

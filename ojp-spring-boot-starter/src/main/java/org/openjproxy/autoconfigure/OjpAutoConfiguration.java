package org.openjproxy.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for OJP (Open J Proxy).
 *
 * <p>This auto-configuration activates when:</p>
 * <ol>
 *   <li>The OJP JDBC driver class ({@code org.openjproxy.jdbc.Driver}) is on the classpath.</li>
 *   <li>The property {@code spring.datasource.url} starts with {@code jdbc:ojp} (checked via
 *       the {@code spring.datasource.url} property containing the OJP prefix).</li>
 * </ol>
 *
 * <p>What this auto-configuration does:</p>
 * <ul>
 *   <li>Enables {@link OjpProperties} so OJP settings can be configured in
 *       {@code application.properties} under the {@code ojp.*} prefix.</li>
 *   <li>Registers an {@link OjpSystemPropertiesBridge} bean that propagates
 *       {@code ojp.*} properties from Spring's {@link org.springframework.core.env.Environment}
 *       to JVM system properties, where the OJP driver's
 *       {@code DatasourcePropertiesLoader} reads them with the highest precedence.</li>
 * </ul>
 *
 * <p>The auto-configuration runs <em>before</em> {@link DataSourceAutoConfiguration} so
 * that system properties are set before any {@code DataSource} bean is created.</p>
 *
 * <p><strong>Minimal {@code application.properties} required to use OJP:</strong></p>
 * <pre>
 * spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/mydb
 * spring.datasource.username=user
 * spring.datasource.password=secret
 * </pre>
 *
 * <p>The {@link OjpEnvironmentPostProcessor} automatically sets
 * {@code spring.datasource.driver-class-name} and {@code spring.datasource.type} when
 * the OJP URL is detected, so users do not need to configure those manually.</p>
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(name = "org.openjproxy.jdbc.Driver")
@ConditionalOnProperty(prefix = "spring.datasource", name = "url", matchIfMissing = false)
@EnableConfigurationProperties(OjpProperties.class)
public class OjpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OjpAutoConfiguration.class);

    /**
     * Registers the {@link OjpSystemPropertiesBridge} bean.
     *
     * <p>This bean bridges OJP configuration from Spring Boot's {@code application.properties}
     * to JVM system properties, making the settings available to the OJP driver's
     * {@code DatasourcePropertiesLoader} before the first database connection is made.</p>
     *
     * @param ojpProperties the bound {@link OjpProperties}
     * @return the system properties bridge
     */
    @Bean
    public OjpSystemPropertiesBridge ojpSystemPropertiesBridge(OjpProperties ojpProperties) {
        log.debug("Registering OjpSystemPropertiesBridge");
        return new OjpSystemPropertiesBridge(ojpProperties);
    }
}

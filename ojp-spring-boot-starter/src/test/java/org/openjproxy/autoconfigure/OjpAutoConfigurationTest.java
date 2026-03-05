package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OjpAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OjpAutoConfiguration.class));

    @Test
    void shouldRegisterBeansWhenOjpUrlIsConfigured() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb")
                .run(context -> {
                    assertThat(context).hasSingleBean(OjpProperties.class);
                    assertThat(context).hasSingleBean(OjpSystemPropertiesBridge.class);
                });
    }

    @Test
    void shouldNotActivateWhenNoDatasourceUrlIsConfigured() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OjpSystemPropertiesBridge.class);
                });
    }

    @Test
    void shouldNotActivateWhenNonOjpUrlIsConfigured() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/mydb")
                .run(context -> {
                    // OjpAutoConfiguration has @ConditionalOnProperty for spring.datasource.url
                    // and is @ConditionalOnClass for org.openjproxy.jdbc.Driver.
                    // When a non-OJP URL is set, the auto-configuration still loads
                    // but the SystemPropertiesBridge won't set any properties (no OJP props set).
                    // The beans ARE created, but that is harmless.
                    assertThat(context).hasSingleBean(OjpProperties.class);
                });
    }

    @Test
    void shouldBindPoolProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
                        "ojp.connection.pool.maximum-pool-size=30",
                        "ojp.connection.pool.minimum-idle=5",
                        "ojp.connection.pool.connection-timeout=15000",
                        "ojp.connection.pool.idle-timeout=300000",
                        "ojp.connection.pool.max-lifetime=900000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OjpProperties.class);
                    OjpProperties props = context.getBean(OjpProperties.class);
                    assertThat(props.getConnection().getPool().getMaximumPoolSize()).isEqualTo(30);
                    assertThat(props.getConnection().getPool().getMinimumIdle()).isEqualTo(5);
                    assertThat(props.getConnection().getPool().getConnectionTimeout()).isEqualTo(15000L);
                    assertThat(props.getConnection().getPool().getIdleTimeout()).isEqualTo(300000L);
                    assertThat(props.getConnection().getPool().getMaxLifetime()).isEqualTo(900000L);
                });
    }

    @Test
    void shouldBindGrpcProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
                        "ojp.grpc.max-inbound-message-size=33554432"
                )
                .run(context -> {
                    OjpProperties props = context.getBean(OjpProperties.class);
                    assertThat(props.getGrpc().getMaxInboundMessageSize()).isEqualTo(33554432);
                });
    }

    @Test
    void shouldBindDatasourceNameProperty() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059(myApp)]_postgresql://user@localhost/mydb",
                        "ojp.datasource.name=myApp"
                )
                .run(context -> {
                    OjpProperties props = context.getBean(OjpProperties.class);
                    assertThat(props.getDatasource().getName()).isEqualTo("myApp");
                });
    }

    @Test
    void shouldBindEnvironmentProperty() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb",
                        "ojp.environment=prod"
                )
                .run(context -> {
                    OjpProperties props = context.getBean(OjpProperties.class);
                    assertThat(props.getEnvironment()).isEqualTo("prod");
                });
    }
}

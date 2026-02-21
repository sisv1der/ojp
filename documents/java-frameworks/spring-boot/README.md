# Spring boot

To integrate OJP into your Spring Boot project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-beta</version>
</dependency>
```

## 2 Remove the local connection pool
Spring boot by default comes with HikariCP connection pool, as OJP replaces completely the connection pool, remove it in the pom.xml as follows.
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <!--When using OJP proxied connection pool the local pool needs to be removed -->
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```
## 3 Change your connection URL
In your application.properties(or yaml) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:

```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_h2:~/test
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
## Sets the datasource to not use hikari CP connection pool and open single connections instead
spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource
``` 

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.

## Troubleshooting 
### Logging Configuration

As of OJP version 0.3.2, the logging implementation has been updated to be compatible with Spring Boot's default logging framework, Logback.

**What Changed:**
- **OJP JDBC Driver**: No longer bundles any SLF4J implementation. It only uses the SLF4J API with `provided` scope, allowing the consuming application to choose the logging implementation.
- **OJP Server**: Uses Logback as the logging implementation with configurable options.

**Benefits:**
- ✅ No more logging conflicts when using OJP JDBC driver with Spring Boot
- ✅ Seamless integration with Spring Boot's existing logging configuration
- ✅ The consuming application (like your Spring Boot app) provides the logging implementation
- ✅ Consistent logging across your entire application

**OJP Server Logging Configuration:**

For detailed information about configuring OJP Server logging (log levels, file locations, rotation policies), see the [OJP Server Configuration Guide](../../configuration/ojp-server-configuration.md#logging-settings).

**For older versions (0.3.2-beta and earlier):**

If you're using an older version of OJP, you may encounter a conflict because the OJP JDBC driver bundled SLF4J Simple, which conflicts with Spring Boot's default Logback implementation.

The error typically looks like this:

```shell
SLF4J(W): Class path contains multiple SLF4J providers.
SLF4J(W): Found provider [org.slf4j.simple.SimpleServiceProvider@75412c2f]
SLF4J(W): Found provider [ch.qos.logback.classic.spi.LogbackServiceProvider@282ba1e]
Exception in thread "main" java.lang.IllegalStateException: LoggerFactory is not a Logback LoggerContext...
```

**Solution for older versions:**

Option 1 (Recommended): Upgrade to OJP 0.3.2 or later, which has this issue resolved.

Option 2: If you must use an older version, you can work around the issue by adding a JVM argument:
```shell
JAVA_OPTS="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
```

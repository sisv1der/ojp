# Quarkus

To integrate OJP into your Quarkus project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>[TBD]</version>
</dependency>
```

## 2 Disable quarkus default connection pool

```properties 
## Use unpooled datasource 

quarkus.datasource.jdbc=true
quarkus.datasource.jdbc.unpooled=true
```

## 3 Change your connection URL
In your `application.properties` (or `application.yml`) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:
```properties
quarkus.datasource.jdbc.url=jdbc:ojp[localhost:1059]_h2:mem:shopdb
quarkus.datasource.jdbc.driver=org.openjproxy.jdbc.Driver
```

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.

> **Note:** For Quarkus projects, all OJP configuration (connection pool settings, health check
> settings, etc.) can be placed directly in your `application.properties` or `application.yml`.
> A separate `ojp.properties` file is not required — Quarkus reads OJP properties from its
> standard configuration sources. Use whichever property naming style is most readable for your
> team; kebab-case (e.g. `retry-attempts`) and camelCase (e.g. `retryAttempts`) are both accepted
> by the OJP driver.

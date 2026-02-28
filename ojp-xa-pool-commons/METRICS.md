# OpenTelemetry Metrics for Connection Pools

## Overview

OJP now exposes comprehensive connection pool metrics to OpenTelemetry for all supported pool implementations: XA pools (Commons Pool 2), HikariCP, and DBCP. These metrics provide deep visibility into pool health, performance, and resource utilization, similar to how HikariCP's native metrics work.

## Metrics Exposed

### XA Pool Metrics (Commons Pool 2)

All XA pool metrics are prefixed with `ojp.xa.pool` and tagged with the `pool.name` attribute for identification.

#### Gauge Metrics (Current State)

| Metric Name | Type | Unit | Description |
|------------|------|------|-------------|
| `ojp.xa.pool.connections.active` | Gauge | connections | Number of connections currently borrowed from the pool |
| `ojp.xa.pool.connections.idle` | Gauge | connections | Number of idle connections available in the pool |
| `ojp.xa.pool.connections.pending` | Gauge | threads | Number of threads waiting to borrow a connection |
| `ojp.xa.pool.connections.max` | Gauge | connections | Maximum pool size (configurable) |
| `ojp.xa.pool.connections.min` | Gauge | connections | Minimum idle connections maintained |
| `ojp.xa.pool.connections.utilization` | Gauge | percent | Pool utilization percentage (0-100) calculated as `(active/max) * 100` |
| `ojp.xa.pool.connections.created` | Gauge | connections | Total connections created since pool start |
| `ojp.xa.pool.connections.destroyed` | Gauge | connections | Total connections destroyed since pool start |

### HikariCP Pool Metrics

All HikariCP metrics are prefixed with `ojp.hikari.pool` and tagged with the `pool.name` attribute for identification.

#### Gauge Metrics (Current State)

| Metric Name | Type | Unit | Description |
|------------|------|------|-------------|
| `ojp.hikari.pool.connections.active` | Gauge | connections | Number of connections currently in use |
| `ojp.hikari.pool.connections.idle` | Gauge | connections | Number of idle connections in the pool |
| `ojp.hikari.pool.connections.total` | Gauge | connections | Total connections in the pool (active + idle) |
| `ojp.hikari.pool.connections.pending` | Gauge | threads | Number of threads waiting for connections |
| `ojp.hikari.pool.connections.max` | Gauge | connections | Maximum pool size (configured) |
| `ojp.hikari.pool.connections.min` | Gauge | connections | Minimum idle connections maintained |

### XA Pool Counter Metrics (Events)

| Metric Name | Type | Unit | Description |
|------------|------|------|-------------|
| `ojp.xa.pool.connections.exhausted` | Counter | events | Number of times the pool was exhausted (borrow timeout) |
| `ojp.xa.pool.connections.validation.failed` | Counter | failures | Number of connection validation failures |
| `ojp.xa.pool.connections.leaks.detected` | Counter | leaks | Number of connection leaks detected |
| `ojp.xa.pool.connections.acquisition.time` | Counter | milliseconds | Total time spent acquiring connections (sum) |
| `ojp.xa.pool.connections.acquisition.count` | Counter | acquisitions | Number of connection acquisitions tracked |

## Configuration

Metrics collection is controlled by the `ojp.telemetry.pool.metrics.enabled` flag. When enabled (default), metrics are collected for all pool types (XA, HikariCP, DBCP).

```properties
# Enable/disable pool metrics collection (default: enabled when OpenTelemetry is enabled)
ojp.telemetry.pool.metrics.enabled=true

# Set a custom pool name for metrics labeling (default: ojp-xa-pool for XA pools)
ojp.xa.poolName=my-app-xa-pool

# For backward compatibility, legacy config keys are also supported:
# ojp.xa.metrics.enabled=true
# xa.metrics.enabled=true
# xa.poolName=my-app-xa-pool
```

### Java Configuration Example

```java
Map<String, String> config = new HashMap<>();
config.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");
config.put("xa.url", "jdbc:postgresql://localhost:5432/mydb");
config.put("xa.username", "postgres");
config.put("xa.password", "secret");
config.put("xa.maxPoolSize", "20");
config.put("ojp.xa.poolName", "orders-service-xa-pool");
config.put("ojp.telemetry.pool.metrics.enabled", "true");

XADataSource xaDS = provider.createXADataSource(config);
```

### JVM Arguments for ojp-server

When running ojp-server, use these JVM arguments to enable metrics:

```bash
-Dojp.telemetry.enabled=true                    # Enable OpenTelemetry infrastructure
-Dojp.prometheus.port=9159                      # Prometheus endpoint port
-Dojp.prometheus.allowedIps=127.0.0.1,10.0.0.0/8
-Dojp.telemetry.pool.metrics.enabled=true      # Enable pool metrics (XA, HikariCP, DBCP)
-Dojp.telemetry.grpc.metrics.enabled=true      # Enable gRPC metrics (default: true)
```

## Integration with OpenTelemetry

### Automatic Integration

Metrics are automatically collected when OpenTelemetry is enabled in ojp-server (`ojp.telemetry.enabled=true`) and pool metrics are enabled (`ojp.telemetry.pool.metrics.enabled=true`, which is the default). The OpenTelemetry instance is registered globally and used by all pools (XA, HikariCP, DBCP). The metrics will be exported through whatever exporters are configured in your OpenTelemetry SDK setup (Prometheus, OTLP, etc.).

### Configuration Hierarchy

The telemetry system uses a three-tier configuration approach:

1. **Master Switch** (`ojp.telemetry.enabled`): Controls whether to initialize the OpenTelemetry SDK and Prometheus server. When false, the system uses no-op telemetry with zero overhead, and no telemetry infrastructure is started (no Prometheus port opened, no exporters initialized).

2. **gRPC Metrics** (`ojp.telemetry.grpc.metrics.enabled`): Controls collection of gRPC server metrics. Only takes effect when the master switch is enabled. Default: true.

3. **Pool Metrics** (`ojp.telemetry.pool.metrics.enabled`): Controls collection of connection pool metrics (XA, HikariCP, DBCP). Only takes effect when the master switch is enabled. Default: true.

This design provides clear separation of concerns: the master switch manages infrastructure setup and resource allocation, while the granular flags control which specific metrics are collected within an already-initialized OpenTelemetry system.

### With Prometheus Exporter

If using the Prometheus exporter (as configured in `ojp-server`), metrics will be available at the Prometheus endpoint:

```
http://localhost:9159/metrics
```

Example Prometheus metrics output:

```
# XA Pool Metrics
# HELP ojp_xa_pool_connections_active Number of active (borrowed) connections
# TYPE ojp_xa_pool_connections_active gauge
ojp_xa_pool_connections_active{pool_name="ojp-xa-pool"} 5.0

# HELP ojp_xa_pool_connections_idle Number of idle connections in pool
# TYPE ojp_xa_pool_connections_idle gauge
ojp_xa_pool_connections_idle{pool_name="ojp-xa-pool"} 15.0

# HELP ojp_xa_pool_connections_utilization Pool utilization percentage (0-100)
# TYPE ojp_xa_pool_connections_utilization gauge
ojp_xa_pool_connections_utilization{pool_name="ojp-xa-pool"} 25.0

# HELP ojp_xa_pool_connections_exhausted_total Pool exhaustion events
# TYPE ojp_xa_pool_connections_exhausted_total counter
ojp_xa_pool_connections_exhausted_total{pool_name="ojp-xa-pool"} 0.0

# HikariCP Pool Metrics
# HELP ojp_hikari_pool_connections_active Number of active connections
# TYPE ojp_hikari_pool_connections_active gauge
ojp_hikari_pool_connections_active{pool_name="my-hikari-pool"} 8.0

# HELP ojp_hikari_pool_connections_idle Number of idle connections
# TYPE ojp_hikari_pool_connections_idle gauge
ojp_hikari_pool_connections_idle{pool_name="my-hikari-pool"} 2.0

# HELP ojp_hikari_pool_connections_total Total connections in pool
# TYPE ojp_hikari_pool_connections_total gauge
ojp_hikari_pool_connections_total{pool_name="my-hikari-pool"} 10.0

# HELP ojp_hikari_pool_connections_pending Threads waiting for connections
# TYPE ojp_hikari_pool_connections_pending gauge
ojp_hikari_pool_connections_pending{pool_name="my-hikari-pool"} 0.0
```

## Monitoring Best Practices

### Key Metrics to Watch

1. **Pool Utilization** (`ojp.xa.pool.connections.utilization`)
   - Consistently high (>80%): Consider increasing pool size
   - Consistently low (<20%): Consider reducing pool size

2. **Pending Threads** (`ojp.xa.pool.connections.pending`)
   - Any non-zero value indicates contention
   - Sustained high values: Increase pool size or optimize connection usage

3. **Pool Exhaustion Events** (`ojp.xa.pool.connections.exhausted`)
   - Should be zero or very low
   - Frequent exhaustion: Increase pool size or reduce connection hold time

4. **Connection Leaks** (`ojp.xa.pool.connections.leaks.detected`)
   - Should always be zero
   - Any leaks indicate application bugs (connections not being returned)

5. **Validation Failures** (`ojp.xa.pool.connections.validation.failed`)
   - Occasional failures are normal (network issues, database restarts)
   - Frequent failures may indicate database health issues

6. **Average Acquisition Time**
   - Calculate as `acquisition.time / acquisition.count`
   - High values indicate pool contention or database latency

### Alerting Recommendations

```yaml
# Example Prometheus alerting rules
groups:
  - name: ojp_xa_pool
    rules:
      # Alert if pool utilization is consistently high
      - alert: XAPoolHighUtilization
        expr: ojp_xa_pool_connections_utilization > 85
        for: 5m
        annotations:
          summary: "XA pool {{ $labels.pool_name }} is running hot"
          description: "Pool utilization is {{ $value }}%"

      # Alert if pool exhaustion is occurring
      - alert: XAPoolExhausted
        expr: increase(ojp_xa_pool_connections_exhausted_total[5m]) > 0
        annotations:
          summary: "XA pool {{ $labels.pool_name }} is exhausting"
          description: "Pool has exhausted {{ $value }} times in 5 minutes"

      # Alert on connection leaks
      - alert: XAPoolConnectionLeak
        expr: increase(ojp_xa_pool_connections_leaks_detected_total[5m]) > 0
        annotations:
          summary: "Connection leak detected in {{ $labels.pool_name }}"
          description: "{{ $value }} leaks detected in 5 minutes"

      # Alert if threads are waiting for connections
      - alert: XAPoolContention
        expr: ojp_xa_pool_connections_pending > 5
        for: 2m
        annotations:
          summary: "High contention in {{ $labels.pool_name }}"
          description: "{{ $value }} threads waiting for connections"
```

## Grafana Dashboard Example

Key panels to include:

1. **Pool Overview**
   - Active connections (time series)
   - Idle connections (time series)
   - Pool utilization percentage (gauge)

2. **Performance**
   - Average connection acquisition time (graph)
   - Connections created/destroyed rate (graph)

3. **Health**
   - Pending threads (stat)
   - Pool exhaustion events (stat)
   - Validation failures (stat)
   - Connection leaks (stat - should always be 0)

## Architecture

The metrics implementation follows a clean architecture:

- **`PoolMetrics` interface**: Abstraction for metrics collection
- **`NoOpPoolMetrics`**: No-operation implementation when metrics are disabled
- **`OpenTelemetryPoolMetrics`**: OpenTelemetry-based implementation
- **`PoolMetricsFactory`**: Factory to select appropriate implementation based on configuration and availability
- **`MetricsAwareHousekeepingListener`**: Wrapper to integrate metrics with housekeeping events

This design ensures:
- Zero overhead when metrics are disabled
- No hard dependency on OpenTelemetry (provided scope)
- Easy extension for other metrics frameworks (e.g., Micrometer)
- Consistent metric collection across pool operations

## Comparison with HikariCP

Similar to HikariCP's native metrics implementation, OJP's pool metrics provide:

✅ Pool state metrics (active, idle, pending, utilization)  
✅ Connection lifecycle metrics (created, destroyed)  
✅ Performance metrics (acquisition time)  
✅ Health metrics (exhaustion, validation failures)  
✅ OpenTelemetry integration  
✅ Prometheus export support  
✅ Configurable pool naming for multi-pool scenarios  

### HikariCP Integration

For HikariCP pools, OJP leverages HikariCP's built-in `MetricsTrackerFactory` interface to collect metrics. When pool metrics are enabled (`ojp.telemetry.pool.metrics.enabled=true`), an OpenTelemetry-based MetricsTracker is automatically registered with each HikariCP instance. This provides native HikariCP metrics through the same OpenTelemetry infrastructure as XA pools.

The metrics exposed for HikariCP include:
- Active, idle, and total connection counts
- Threads waiting for connections (pending)
- Maximum and minimum pool sizes
- All metrics tagged with the configured pool name

### Additional features specific to XA pools:

✅ Leak detection metrics  
✅ Integration with existing housekeeping system  
✅ Transaction isolation tracking  
✅ More granular lifecycle events (created, destroyed, validation failures)

# Query Result Caching - User Guide

## Overview

OJP query result caching provides automatic caching of SELECT query results to reduce database load and improve query performance. This guide covers configuration, usage, monitoring, and troubleshooting.

## Quick Start

Add cache configuration to your `ojp.properties` file:

```properties
# Enable caching for your datasource
postgres_prod.ojp.cache.enabled=true

# Define cache rule for product queries
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products
```

That's it! Queries matching your pattern will now be cached automatically.

## Configuration

### Basic Configuration

```properties
# Enable or disable caching
<datasource>.ojp.cache.enabled=true

# Define cache rules (multiple rules supported)
<datasource>.ojp.cache.queries.<N>.pattern=<regex>
<datasource>.ojp.cache.queries.<N>.ttl=<duration>
<datasource>.ojp.cache.queries.<N>.invalidateOn=<table1>,<table2>
<datasource>.ojp.cache.queries.<N>.enabled=true
```

### Pattern Syntax

Patterns use Java regular expressions to match SQL queries:

**Basic patterns:**
```properties
# Match exact query
queries.1.pattern=SELECT * FROM users WHERE id = \?

# Match any query on products table
queries.2.pattern=SELECT .* FROM products.*

# Match queries with specific WHERE clause
queries.3.pattern=SELECT .* FROM orders WHERE status = 'PENDING'

# Match category filter queries
queries.4.pattern=SELECT .* FROM products WHERE category IN \(.*\)
```

**Advanced patterns:**
```properties
# Match multiple tables
queries.5.pattern=SELECT .* FROM (products|categories).*

# Match complex queries
queries.6.pattern=SELECT p\..*,c\..* FROM products p JOIN categories c.*
```

**Tips:**
- Use `.*` to match any characters
- Use `\?` to match parameter placeholders
- Use `\s+` to match whitespace
- Test patterns before deployment
- Keep patterns as specific as possible

### TTL Format

Time-To-Live specifies how long results stay cached:

```properties
# Seconds
queries.1.ttl=300s    # 5 minutes

# Minutes  
queries.2.ttl=10m     # 10 minutes

# Hours
queries.3.ttl=2h      # 2 hours
```

**Guidelines:**
- **Minimum:** 10s (shorter TTLs may cause high database load)
- **Maximum:** 24h (longer TTLs risk serving stale data)
- **Recommended:** 5-30 minutes for most use cases

### Invalidation

Cache entries are automatically invalidated when tables are modified:

```properties
# Invalidate when products table is updated
queries.1.invalidateOn=products

# Invalidate when any of these tables are updated
queries.2.invalidateOn=products,product_prices,product_inventory

# No automatic invalidation (rely only on TTL)
queries.3.invalidateOn=
```

**How it works:**
- INSERT/UPDATE/DELETE statements are analyzed
- Affected tables are extracted automatically
- Cache entries for those tables are invalidated
- Next query will fetch fresh data from database

## Configuration Examples

### E-commerce Product Catalog

```properties
# Product listing (frequently queried, infrequently updated)
ecommerce.ojp.cache.enabled=true
ecommerce.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE category = \?
ecommerce.ojp.cache.queries.1.ttl=600s
ecommerce.ojp.cache.queries.1.invalidateOn=products

# Product details (very frequently queried)
ecommerce.ojp.cache.queries.2.pattern=SELECT .* FROM products WHERE id = \?
ecommerce.ojp.cache.queries.2.ttl=300s
ecommerce.ojp.cache.queries.2.invalidateOn=products,product_prices
```

### User Profile Cache

```properties
# User profile lookups
userdb.ojp.cache.enabled=true
userdb.ojp.cache.queries.1.pattern=SELECT .* FROM users WHERE id = \?
userdb.ojp.cache.queries.1.ttl=600s
userdb.ojp.cache.queries.1.invalidateOn=users

# User permissions (changes less frequently)
userdb.ojp.cache.queries.2.pattern=SELECT .* FROM user_permissions WHERE user_id = \?
userdb.ojp.cache.queries.2.ttl=1800s
userdb.ojp.cache.queries.2.invalidateOn=user_permissions,users
```

### Analytics/Reporting

```properties
# Dashboard queries (can tolerate slight staleness)
analytics.ojp.cache.enabled=true
analytics.ojp.cache.queries.1.pattern=SELECT COUNT\(\*\) FROM orders WHERE.*
analytics.ojp.cache.queries.1.ttl=300s
analytics.ojp.cache.queries.1.invalidateOn=orders

# Report aggregations (expensive queries)
analytics.ojp.cache.queries.2.pattern=SELECT .* FROM daily_stats WHERE date >= \?
analytics.ojp.cache.queries.2.ttl=3600s
analytics.ojp.cache.queries.2.invalidateOn=daily_stats
```

## Monitoring

### OpenTelemetry Metrics

OJP exports cache metrics via OpenTelemetry:

```properties
# Enable cache metrics (default: true when OpenTelemetry enabled)
ojp.telemetry.cache.metrics.enabled=true
```

**Available metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `ojp.cache.hits` | Counter | Cache hits per datasource |
| `ojp.cache.misses` | Counter | Cache misses per datasource |
| `ojp.cache.evictions` | Counter | Cache evictions (size/TTL) |
| `ojp.cache.invalidations` | Counter | Cache invalidations (writes) |
| `ojp.cache.rejections` | Counter | Rejected entries (too large) |
| `ojp.cache.size.entries` | Gauge | Current cache entry count |
| `ojp.cache.size.bytes` | Gauge | Current cache size in bytes |
| `ojp.query.execution.time` | Histogram | Query execution time |
| `ojp.cache.rejection.size` | Histogram | Size of rejected entries |

### Prometheus Queries

```promql
# Cache hit rate
sum(rate(ojp_cache_hits_total[5m])) / 
  (sum(rate(ojp_cache_hits_total[5m])) + sum(rate(ojp_cache_misses_total[5m])))

# Cache size per datasource
ojp_cache_size_bytes{datasource="postgres_prod"}

# Top cached queries
topk(10, sum by(sql_statement) (ojp_cache_hits_total))
```

### Logging

Enable debug logging to see cache operations:

```properties
# In logback.xml or application.properties
logging.level.org.openjproxy.grpc.server.action.transaction.ExecuteQueryAction=DEBUG
```

**Log messages:**
```
[DEBUG] Cache HIT: datasource=postgres_prod, sql=SELECT * FROM products WHERE id = ?
[DEBUG] Cache MISS: datasource=postgres_prod, sql=SELECT * FROM users WHERE id = ?
[DEBUG] Stored in cache: datasource=postgres_prod, rows=10, size=2KB
[WARN] Cache configuration warning: Rule 1: TTL very short (< 10s)
```

## Troubleshooting

### Cache Not Working

**Symptoms:** All queries show "Cache MISS" in logs

**Possible causes:**

1. **Caching not enabled**
   ```properties
   # Check this is set to true
   <datasource>.ojp.cache.enabled=true
   ```

2. **Pattern doesn't match**
   - Test pattern with your actual SQL
   - SQL is normalized (whitespace collapsed)
   - Pattern is case-sensitive

3. **Rule is disabled**
   ```properties
   # Ensure enabled (or omit - defaults to true)
   <datasource>.ojp.cache.queries.1.enabled=true
   ```

4. **Check validation errors in logs**
   ```
   [ERROR] Cache configuration rejected: Invalid regex pattern
   ```

### Low Cache Hit Rate

**Symptoms:** High ratio of misses to hits

**Possible causes:**

1. **Patterns too specific**
   - Use `.*` instead of exact values
   - Match structure, not data

2. **TTL too short**
   - Increase TTL (e.g., from 60s to 600s)
   - Balance freshness vs performance

3. **Parameter variations**
   - Queries with different parameters are cached separately
   - Consider if all parameter combinations should be cached

4. **High write rate**
   - Frequent UPDATEs invalidate cache
   - May not benefit from caching

### High Memory Usage

**Symptoms:** OJP server using excessive memory

**Solutions:**

1. **Reduce cache size limits** (server-side configuration)
2. **Lower TTL values** (entries expire sooner)
3. **More selective patterns** (cache fewer queries)
4. **Exclude large result sets** (queries returning many rows)

### Stale Data

**Symptoms:** Application sees outdated data

**Possible causes:**

1. **TTL too long**
   - Reduce TTL for frequently changing data
   - Consider table update patterns

2. **Missing invalidation tables**
   ```properties
   # Add all tables that affect this query
   queries.1.invalidateOn=products,product_prices,product_inventory
   ```

3. **External updates**
   - Updates from other applications don't invalidate
   - Local-only caching limitation

### Security Warnings

**Symptoms:** Warnings about suspicious SQL in logs

```
[WARN] Suspicious SQL pattern detected in cache key - potential SQL injection
```

**Action:**
- Review the SQL being executed
- Could indicate SQL injection attempt
- Cache key validation prevents caching potentially malicious queries

## Best Practices

### Pattern Design

1. **Start broad, narrow down** - Begin with `.*` patterns, refine based on metrics
2. **Match query structure** - Pattern should match SQL structure, not data values
3. **Test before deploying** - Use regex tester to validate patterns
4. **Document your patterns** - Add comments explaining what each pattern matches

### TTL Selection

1. **Consider data freshness requirements** - How stale can data be?
2. **Consider update frequency** - How often does data change?
3. **Start conservative** - Begin with shorter TTLs, increase if safe
4. **Monitor invalidation rate** - High invalidation = shorter effective TTL

### Invalidation Configuration

1. **List all dependent tables** - Include all tables that affect query results
2. **Consider join relationships** - Include joined tables
3. **Test invalidation** - Verify cache is cleared on UPDATE

### Monitoring

1. **Track hit rate** - Target >70% for cached queries
2. **Monitor cache size** - Ensure memory usage is acceptable
3. **Watch for warnings** - Address configuration warnings
4. **Set up alerts** - Alert on low hit rate or high memory

## Limitations

### Current Implementation (Local-Only)

- **Per-server caching** - Each OJP server has independent cache
- **Write invalidation** - Only local server cache is invalidated
- **No cluster coordination** - Caches not shared between servers

### Future Enhancements

- **Distributed caching** - Share cache across OJP servers
- **Write-through propagation** - Invalidate caches on all servers
- **Advanced analytics** - Per-query hit rates and performance metrics

## Advanced Topics

### Cache Warming

Pre-populate cache on server start:

```sql
-- Execute common queries after deployment
SELECT * FROM products WHERE category = 'electronics';
SELECT * FROM products WHERE category = 'clothing';
```

### Multi-Tenant Configuration

Separate cache configuration per tenant:

```properties
tenant1.ojp.cache.enabled=true
tenant1.ojp.cache.queries.1.pattern=SELECT .* FROM products.*
tenant1.ojp.cache.queries.1.ttl=600s

tenant2.ojp.cache.enabled=true
tenant2.ojp.cache.queries.1.pattern=SELECT .* FROM products.*
tenant2.ojp.cache.queries.1.ttl=300s
```

### Performance Tuning

1. **Optimize pattern matching** - Simpler patterns match faster
2. **Limit result set size** - Large results take longer to cache
3. **Balance cache size** - More cache = more memory but fewer misses
4. **Monitor overhead** - Caching adds ~5-20ms per query

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/wiki

## Version

This guide is for OJP Caching v1.0 (Phases 1-10 complete)

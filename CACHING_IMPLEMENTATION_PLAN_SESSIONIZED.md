# Query Result Caching - Implementation Plan (Session-Sized Phases)

This document provides a detailed implementation plan for query result caching in OJP with **each phase sized to fit comfortably within a single Copilot session** (2-4 hours, ~15-25 files changed).

## ⭐ Scope: Local Caching Only

**Focus:** Local query result caching on each OJP server independently  
**Distribution:** Excluded from this plan (deferred to future phases)  
**Timeline:** 14 weeks (3.5 months)  
**Team:** 4 people (1 Senior Java Dev full-time, 1 DevOps part-time, 1 QA part-time, 1 Tech Lead oversight)

---

## Phase Overview

| Phase | Week | Scope | Files Changed | Copilot Session |
|-------|------|-------|---------------|-----------------|
| 1 | 1 | Core data structures | ~5-8 files | ✅ Single session |
| 2 | 2 | Configuration parsing | ~6-10 files | ✅ Single session |
| 3 | 3 | Cache storage (Caffeine) | ~8-12 files | ✅ Single session |
| 4 | 4 | Protocol updates (proto) | ~10-15 files | ✅ Single session |
| 5 | 5 | Session integration | ~8-12 files | ✅ Single session |
| 6 | 6 | Query execution - lookup | ~10-15 files | ✅ Single session |
| 7 | 7 | Query execution - storage | ~8-12 files | ✅ Single session |
| 8 | 8 | SQL table extraction | ~10-15 files | ✅ Single session |
| 9 | 9 | Write invalidation | ~10-15 files | ✅ Single session |
| 10 | 10 | Monitoring & metrics | ~12-18 files | ✅ Single session |
| 11 | 11 | Production hardening | ~10-15 files | ✅ Single session |
| 12 | 12 | Unit & integration tests | ~15-20 files | ✅ Single session |
| 13 | 13 | E2E & performance tests | ~12-18 files | ✅ Single session |
| 14 | 14 | Production deployment | Config/docs only | ✅ Single session |

---

## Phase 1: Core Data Structures (Week 1)

**Goal:** Create foundational classes for caching without any integration  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~5-8 files

### Deliverables

1. **`QueryCacheKey.java`** - Immutable cache key with datasource, SQL, parameters
2. **`CachedQueryResult.java`** - Cache entry with data, metadata, expiration
3. **`CacheRule.java`** - Single cache rule (pattern, TTL, invalidation)
4. **`CacheConfiguration.java`** - Collection of cache rules for a datasource

### Implementation

#### 1.1 QueryCacheKey

```java
package org.openjproxy.cache;

public final class QueryCacheKey {
    private final String datasourceName;
    private final String normalizedSql;  // Whitespace normalized
    private final List<Object> parameters;
    private final int hashCode;  // Pre-computed

    public QueryCacheKey(String datasourceName, String sql, List<Object> parameters) {
        this.datasourceName = requireNonNull(datasourceName);
        this.normalizedSql = normalizeSql(sql);
        this.parameters = List.copyOf(parameters);  // Immutable
        this.hashCode = computeHashCode();
    }

    private static String normalizeSql(String sql) {
        // Collapse whitespace for cache key consistency
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof QueryCacheKey other)) return false;
        return datasourceName.equals(other.datasourceName)
            && normalizedSql.equals(other.normalizedSql)
            && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return hashCode;  // O(1) lookup
    }
}
```

#### 1.2 CachedQueryResult

```java
package org.openjproxy.cache;

public final class CachedQueryResult {
    private final List<List<Object>> rows;  // Immutable
    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final Instant cachedAt;
    private final Instant expiresAt;
    private final Set<String> affectedTables;  // For invalidation

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public long estimateSize() {
        // Rough estimate for memory management
        long size = 0;
        for (List<Object> row : rows) {
            for (Object value : row) {
                size += estimateValueSize(value);
            }
        }
        return size;
    }

    private long estimateValueSize(Object value) {
        if (value == null) return 8;
        if (value instanceof String s) return 40 + (s.length() * 2);
        if (value instanceof Integer) return 16;
        if (value instanceof Long) return 24;
        // ... more types
        return 32;  // Default estimate
    }
}
```

#### 1.3 CacheRule

```java
package org.openjproxy.cache;

public final class CacheRule {
    private final Pattern sqlPattern;  // Regex pattern
    private final Duration ttl;
    private final List<String> invalidateOn;  // Table names
    private final boolean enabled;

    public boolean matches(String sql) {
        return sqlPattern.matcher(sql).matches();
    }

    public Duration getTtl() {
        return ttl;
    }

    public List<String> getInvalidateOn() {
        return invalidateOn;
    }
}
```

#### 1.4 CacheConfiguration

```java
package org.openjproxy.cache;

public final class CacheConfiguration {
    private final String datasourceName;
    private final boolean enabled;
    private final boolean distribute;  // Always false for local-only
    private final List<CacheRule> rules;  // Ordered by priority

    public CacheRule findMatchingRule(String sql) {
        for (CacheRule rule : rules) {
            if (rule.matches(sql)) {
                return rule;
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
```

### Testing

Create unit tests for each class:
- `QueryCacheKeyTest.java` - Test equality, hashing, SQL normalization
- `CachedQueryResultTest.java` - Test expiration, size estimation
- `CacheRuleTest.java` - Test pattern matching
- `CacheConfigurationTest.java` - Test rule ordering, matching

### Success Criteria

- ✅ All classes immutable and thread-safe
- ✅ Unit tests with >95% coverage
- ✅ No external dependencies (just java.util, java.time)
- ✅ Clean, documented code
- ✅ Passes code review

### Time Estimate

- Implementation: 3-4 hours
- Testing: 2-3 hours
- Code review: 1 hour
- **Total: 1 week (includes buffer)**

---

## Phase 2: Configuration Parsing (Week 2)

**Goal:** Parse cache configuration from ojp.properties  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~6-10 files

### Deliverables

1. **`CacheConfigurationParser.java`** - Parse ojp.properties entries
2. **`CacheConfigurationRegistry.java`** - Store configurations by datasource name
3. Update connection handling to pass configuration
4. Comprehensive parsing tests

### Implementation

#### 2.1 CacheConfigurationParser

```java
package org.openjproxy.cache;

public class CacheConfigurationParser {
    
    /**
     * Parse cache configuration from System properties.
     * 
     * Expected format:
     * postgres_prod.ojp.cache.enabled=true
     * postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
     * postgres_prod.ojp.cache.queries.1.ttl=600s
     * postgres_prod.ojp.cache.queries.1.invalidateOn=products
     */
    public static CacheConfiguration parse(String datasourceName) {
        String prefix = datasourceName + ".ojp.cache.";
        
        boolean enabled = getBooleanProperty(prefix + "enabled", false);
        if (!enabled) {
            return CacheConfiguration.disabled(datasourceName);
        }
        
        boolean distribute = getBooleanProperty(prefix + "distribute", false);
        
        // Parse query rules
        List<CacheRule> rules = parseQueryRules(prefix);
        
        return new CacheConfiguration(datasourceName, enabled, distribute, rules);
    }
    
    private static List<CacheRule> parseQueryRules(String prefix) {
        List<CacheRule> rules = new ArrayList<>();
        
        // Find all query indices (1, 2, 3, ...)
        Set<Integer> indices = findQueryIndices(prefix);
        
        for (int index : indices) {
            String queryPrefix = prefix + "queries." + index + ".";
            
            String pattern = getProperty(queryPrefix + "pattern");
            if (pattern == null) continue;
            
            String ttlStr = getProperty(queryPrefix + "ttl", "300s");
            Duration ttl = parseDuration(ttlStr);
            
            String invalidateOnStr = getProperty(queryPrefix + "invalidateOn", "");
            List<String> invalidateOn = parseTableList(invalidateOnStr);
            
            rules.add(new CacheRule(Pattern.compile(pattern), ttl, invalidateOn, true));
        }
        
        return rules;
    }
    
    private static Set<Integer> findQueryIndices(String prefix) {
        // Scan System properties for all indices
        Set<Integer> indices = new TreeSet<>();
        String queriesPrefix = prefix + "queries.";
        
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(queriesPrefix)) {
                String rest = key.substring(queriesPrefix.length());
                int dotIndex = rest.indexOf('.');
                if (dotIndex > 0) {
                    try {
                        int index = Integer.parseInt(rest.substring(0, dotIndex));
                        indices.add(index);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        
        return indices;
    }
    
    private static Duration parseDuration(String ttlStr) {
        // Parse "300s", "10m", "1h"
        ttlStr = ttlStr.trim().toLowerCase();
        
        if (ttlStr.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1)));
        } else if (ttlStr.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1)));
        } else if (ttlStr.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1)));
        } else {
            throw new IllegalArgumentException("Invalid TTL format: " + ttlStr);
        }
    }
    
    private static List<String> parseTableList(String invalidateOnStr) {
        if (invalidateOnStr.isBlank()) {
            return List.of();
        }
        return Arrays.stream(invalidateOnStr.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
```

#### 2.2 CacheConfigurationRegistry

```java
package org.openjproxy.cache;

public class CacheConfigurationRegistry {
    private static final CacheConfigurationRegistry INSTANCE = new CacheConfigurationRegistry();
    private final ConcurrentHashMap<String, CacheConfiguration> configurations = new ConcurrentHashMap<>();
    
    public static CacheConfigurationRegistry getInstance() {
        return INSTANCE;
    }
    
    public CacheConfiguration getOrLoad(String datasourceName) {
        return configurations.computeIfAbsent(
            datasourceName,
            CacheConfigurationParser::parse
        );
    }
    
    public void reload(String datasourceName) {
        configurations.remove(datasourceName);
        getOrLoad(datasourceName);
    }
    
    public void clear() {
        configurations.clear();
    }
}
```

### Testing

Create comprehensive parsing tests:
- `CacheConfigurationParserTest.java` - Test all property formats
- Test error handling (invalid patterns, malformed TTLs)
- Test defaults (enabled=false, ttl=300s)
- Test multiple queries per datasource
- Test table list parsing (comma-separated)

### Example Test

```java
@Test
public void testParseValidConfiguration() {
    System.setProperty("testds.ojp.cache.enabled", "true");
    System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
    System.setProperty("testds.ojp.cache.queries.1.ttl", "600s");
    System.setProperty("testds.ojp.cache.queries.1.invalidateOn", "products");
    
    CacheConfiguration config = CacheConfigurationParser.parse("testds");
    
    assertTrue(config.isEnabled());
    assertEquals(1, config.getRules().size());
    
    CacheRule rule = config.getRules().get(0);
    assertEquals(Duration.ofSeconds(600), rule.getTtl());
    assertEquals(List.of("products"), rule.getInvalidateOn());
}
```

### Success Criteria

- ✅ Parser handles all property formats correctly
- ✅ Error messages are clear and actionable
- ✅ Defaults are sensible (enabled=false, ttl=300s)
- ✅ Unit tests with >95% coverage
- ✅ Integration test with real properties file

### Time Estimate

- Implementation: 3-4 hours
- Testing: 2-3 hours
- Integration test: 1 hour
- **Total: 1 week**

---

## Phase 3: Cache Storage (Week 3)

**Goal:** Implement cache storage using Caffeine library  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~8-12 files

### Deliverables

1. **`QueryResultCache.java`** - Main cache implementation using Caffeine
2. **`CacheStatistics.java`** - Track hits, misses, evictions
3. Add Caffeine dependency to pom.xml
4. Cache management and eviction tests

### Implementation

#### 3.1 Add Caffeine Dependency

```xml
<!-- In ojp-server/pom.xml -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

#### 3.2 QueryResultCache

```java
package org.openjproxy.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

public class QueryResultCache {
    private final Cache<QueryCacheKey, CachedQueryResult> cache;
    private final CacheStatistics statistics;
    private final long maxSizeBytes;
    private final AtomicLong currentSizeBytes = new AtomicLong(0);
    
    public QueryResultCache(int maxEntries, Duration maxAge, long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
        this.statistics = new CacheStatistics();
        
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(maxAge)
            .removalListener(this::onRemoval)
            .recordStats()
            .build();
    }
    
    public CachedQueryResult get(QueryCacheKey key) {
        CachedQueryResult result = cache.getIfPresent(key);
        
        if (result == null) {
            statistics.recordMiss();
            return null;
        }
        
        // Check if expired (belt and suspenders - Caffeine should handle this)
        if (result.isExpired()) {
            cache.invalidate(key);
            statistics.recordMiss();
            return null;
        }
        
        statistics.recordHit();
        return result;
    }
    
    public void put(QueryCacheKey key, CachedQueryResult result) {
        long resultSize = result.estimateSize();
        
        // Check size limit
        while (currentSizeBytes.get() + resultSize > maxSizeBytes && cache.estimatedSize() > 0) {
            // Trigger eviction by clearing oldest entries
            cache.cleanUp();
            
            // If still too large, don't cache this result
            if (currentSizeBytes.get() + resultSize > maxSizeBytes) {
                statistics.recordRejection();
                return;
            }
        }
        
        cache.put(key, result);
        currentSizeBytes.addAndGet(resultSize);
    }
    
    public void invalidate(String datasourceName, Set<String> tables) {
        if (tables.isEmpty()) {
            return;
        }
        
        // Scan cache for entries that depend on these tables
        cache.asMap().entrySet().removeIf(entry -> {
            QueryCacheKey key = entry.getKey();
            CachedQueryResult value = entry.getValue();
            
            // Check if key belongs to this datasource
            if (!key.getDatasourceName().equals(datasourceName)) {
                return false;
            }
            
            // Check if result depends on any of the affected tables
            for (String table : tables) {
                if (value.getAffectedTables().contains(table.toLowerCase())) {
                    statistics.recordInvalidation();
                    return true;
                }
            }
            
            return false;
        });
    }
    
    public void invalidateAll() {
        cache.invalidateAll();
        currentSizeBytes.set(0);
    }
    
    private void onRemoval(QueryCacheKey key, CachedQueryResult value, RemovalCause cause) {
        if (value != null) {
            currentSizeBytes.addAndGet(-value.estimateSize());
        }
        
        if (cause == RemovalCause.SIZE || cause == RemovalCause.EXPIRED) {
            statistics.recordEviction();
        }
    }
    
    public CacheStatistics getStatistics() {
        return statistics;
    }
    
    public long getCurrentSizeBytes() {
        return currentSizeBytes.get();
    }
    
    public long getEntryCount() {
        return cache.estimatedSize();
    }
}
```

#### 3.3 CacheStatistics

```java
package org.openjproxy.cache;

public class CacheStatistics {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong invalidations = new AtomicLong(0);
    private final AtomicLong rejections = new AtomicLong(0);
    
    public void recordHit() {
        hits.incrementAndGet();
    }
    
    public void recordMiss() {
        misses.incrementAndGet();
    }
    
    public void recordEviction() {
        evictions.incrementAndGet();
    }
    
    public void recordInvalidation() {
        invalidations.incrementAndGet();
    }
    
    public void recordRejection() {
        rejections.incrementAndGet();
    }
    
    public double getHitRate() {
        long h = hits.get();
        long m = misses.get();
        return (h + m == 0) ? 0.0 : (double) h / (h + m);
    }
    
    public long getHits() { return hits.get(); }
    public long getMisses() { return misses.get(); }
    public long getEvictions() { return evictions.get(); }
    public long getInvalidations() { return invalidations.get(); }
    public long getRejections() { return rejections.get(); }
    
    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        invalidations.set(0);
        rejections.set(0);
    }
}
```

### Testing

Create comprehensive cache tests:
- `QueryResultCacheTest.java` - Test put, get, eviction, invalidation
- Test TTL expiration
- Test size-based eviction
- Test invalidation by table name
- Test thread safety (concurrent access)
- Test statistics tracking

### Success Criteria

- ✅ Cache operations are thread-safe
- ✅ TTL expiration works correctly
- ✅ Size limits are respected
- ✅ Invalidation by table name works
- ✅ Statistics are accurate
- ✅ Unit tests with >95% coverage

### Time Estimate

- Add dependency: 30 minutes
- Implementation: 3-4 hours
- Testing: 2-3 hours
- Performance testing: 1 hour
- **Total: 1 week**

---

## Phase 4: Protocol Updates (Week 4)

**Goal:** Update gRPC protocol to transmit cache configuration from client to server  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~10-15 files

### Deliverables

1. Update `connection.proto` to include cache configuration
2. Generate Java classes from proto
3. Update JDBC driver to send cache config
4. Update server to receive and parse cache config
5. Protocol compatibility tests

### Implementation

#### 4.1 Update connection.proto

```protobuf
// In ojp-protocol/src/main/proto/connection.proto

message ConnectionRequest {
    string datasourceName = 1;
    string username = 2;
    string password = 3;
    map<string, string> properties = 4;
    CacheConfiguration cacheConfig = 5;  // NEW
}

message CacheConfiguration {
    bool enabled = 1;
    bool distribute = 2;  // Always false for local-only
    repeated CacheRule rules = 3;
}

message CacheRule {
    string sqlPattern = 1;  // Regex pattern
    int64 ttlSeconds = 2;
    repeated string invalidateOn = 3;  // Table names
    bool enabled = 4;
}
```

#### 4.2 Update JDBC Driver

```java
// In ojp-jdbc-driver/.../OjpConnection.java

public class OjpConnection implements Connection {
    
    @Override
    public void connect(String datasourceName, Properties info) {
        // Parse cache configuration from System properties
        CacheConfiguration cacheConfig = buildCacheConfiguration(datasourceName);
        
        // Send connection request with cache config
        ConnectionRequest request = ConnectionRequest.newBuilder()
            .setDatasourceName(datasourceName)
            .setUsername(info.getProperty("user"))
            .setPassword(info.getProperty("password"))
            .setCacheConfig(cacheConfig)  // NEW
            .build();
        
        ConnectionResponse response = connectionStub.connect(request);
        this.sessionId = response.getSessionId();
    }
    
    private CacheConfiguration buildCacheConfiguration(String datasourceName) {
        String prefix = datasourceName + ".ojp.cache.";
        
        boolean enabled = Boolean.parseBoolean(
            System.getProperty(prefix + "enabled", "false")
        );
        
        if (!enabled) {
            return CacheConfiguration.newBuilder()
                .setEnabled(false)
                .build();
        }
        
        boolean distribute = Boolean.parseBoolean(
            System.getProperty(prefix + "distribute", "false")
        );
        
        CacheConfiguration.Builder builder = CacheConfiguration.newBuilder()
            .setEnabled(true)
            .setDistribute(distribute);
        
        // Parse query rules
        Set<Integer> indices = findQueryIndices(prefix);
        for (int index : indices) {
            CacheRule rule = parseQueryRule(prefix, index);
            if (rule != null) {
                builder.addRules(rule);
            }
        }
        
        return builder.build();
    }
    
    private CacheRule parseQueryRule(String prefix, int index) {
        String queryPrefix = prefix + "queries." + index + ".";
        
        String pattern = System.getProperty(queryPrefix + "pattern");
        if (pattern == null) return null;
        
        String ttlStr = System.getProperty(queryPrefix + "ttl", "300s");
        long ttlSeconds = parseDurationToSeconds(ttlStr);
        
        String invalidateOnStr = System.getProperty(queryPrefix + "invalidateOn", "");
        List<String> invalidateOn = parseTableList(invalidateOnStr);
        
        return CacheRule.newBuilder()
            .setSqlPattern(pattern)
            .setTtlSeconds(ttlSeconds)
            .addAllInvalidateOn(invalidateOn)
            .setEnabled(true)
            .build();
    }
}
```

#### 4.3 Update Server

```java
// In ojp-server/.../ConnectionServiceImpl.java

public class ConnectionServiceImpl extends ConnectionServiceGrpc.ConnectionServiceImplBase {
    
    @Override
    public void connect(ConnectionRequest request, StreamObserver<ConnectionResponse> responseObserver) {
        try {
            // Create session
            String sessionId = UUID.randomUUID().toString();
            
            // Parse cache configuration from request
            CacheConfiguration protoCacheConfig = request.getCacheConfig();
            org.openjproxy.cache.CacheConfiguration cacheConfig = 
                convertProtoCacheConfig(protoCacheConfig, request.getDatasourceName());
            
            // Create and store session with cache config
            ServerSession session = new ServerSession(
                sessionId,
                request.getDatasourceName(),
                request.getUsername(),
                cacheConfig  // NEW
            );
            
            sessionManager.registerSession(session);
            
            ConnectionResponse response = ConnectionResponse.newBuilder()
                .setSessionId(sessionId)
                .setSuccess(true)
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Connection failed: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    private org.openjproxy.cache.CacheConfiguration convertProtoCacheConfig(
            CacheConfiguration protoConfig, 
            String datasourceName) {
        
        if (!protoConfig.getEnabled()) {
            return org.openjproxy.cache.CacheConfiguration.disabled(datasourceName);
        }
        
        List<org.openjproxy.cache.CacheRule> rules = new ArrayList<>();
        
        for (CacheRule protoRule : protoConfig.getRulesList()) {
            Pattern pattern = Pattern.compile(protoRule.getSqlPattern());
            Duration ttl = Duration.ofSeconds(protoRule.getTtlSeconds());
            List<String> invalidateOn = protoRule.getInvalidateOnList();
            
            rules.add(new org.openjproxy.cache.CacheRule(
                pattern,
                ttl,
                invalidateOn,
                protoRule.getEnabled()
            ));
        }
        
        return new org.openjproxy.cache.CacheConfiguration(
            datasourceName,
            true,
            protoConfig.getDistribute(),
            rules
        );
    }
}
```

### Testing

Create protocol tests:
- Test proto serialization/deserialization
- Test configuration transmission from driver to server
- Test backwards compatibility (old clients, new servers)
- Integration test: full connection with cache config

### Success Criteria

- ✅ Proto changes compile and generate Java classes
- ✅ Configuration transmitted correctly
- ✅ Backwards compatible (gracefully handle missing fields)
- ✅ Integration test passes
- ✅ No performance regression

### Time Estimate

- Proto updates: 1-2 hours
- Driver changes: 2-3 hours
- Server changes: 2-3 hours
- Testing: 2-3 hours
- **Total: 1 week**

---

## Phase 5: Session Integration (Week 5)

**Goal:** Store cache configuration in ServerSession for use during query execution  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~8-12 files

### Deliverables

1. Update `ServerSession` to store `CacheConfiguration`
2. Make configuration accessible to actions
3. Add session lifecycle hooks for cache cleanup
4. Session management tests

### Implementation

#### 5.1 Update ServerSession

```java
// In ojp-server/.../ServerSession.java

public class ServerSession {
    private final String sessionId;
    private final String datasourceName;
    private final String username;
    private final CacheConfiguration cacheConfig;  // NEW
    private final Instant createdAt;
    private volatile Instant lastAccessedAt;
    
    public ServerSession(
            String sessionId, 
            String datasourceName, 
            String username,
            CacheConfiguration cacheConfig) {
        this.sessionId = requireNonNull(sessionId);
        this.datasourceName = requireNonNull(datasourceName);
        this.username = requireNonNull(username);
        this.cacheConfig = requireNonNull(cacheConfig);  // NEW
        this.createdAt = Instant.now();
        this.lastAccessedAt = createdAt;
    }
    
    public CacheConfiguration getCacheConfig() {
        return cacheConfig;
    }
    
    public boolean isCachingEnabled() {
        return cacheConfig.isEnabled();
    }
    
    public void updateLastAccessed() {
        this.lastAccessedAt = Instant.now();
    }
}
```

#### 5.2 Update SessionManager

```java
// In ojp-server/.../SessionManager.java

public class SessionManager {
    private final ConcurrentHashMap<String, ServerSession> sessions = new ConcurrentHashMap<>();
    private final QueryResultCache queryCache;  // NEW
    
    public SessionManager(QueryResultCache queryCache) {
        this.queryCache = queryCache;
    }
    
    public void registerSession(ServerSession session) {
        sessions.put(session.getSessionId(), session);
        logger.info("Session registered: id={}, datasource={}, cacheEnabled={}", 
            session.getSessionId(), 
            session.getDatasourceName(),
            session.isCachingEnabled());
    }
    
    public void closeSession(String sessionId) {
        ServerSession session = sessions.remove(sessionId);
        if (session != null) {
            // Optional: Invalidate cache entries for this session
            // (Only if we want per-session caching - probably not needed)
            logger.info("Session closed: id={}, datasource={}", 
                session.getSessionId(), 
                session.getDatasourceName());
        }
    }
    
    public ServerSession getSession(String sessionId) {
        ServerSession session = sessions.get(sessionId);
        if (session != null) {
            session.updateLastAccessed();
        }
        return session;
    }
    
    public QueryResultCache getQueryCache() {
        return queryCache;
    }
}
```

#### 5.3 Update Server Initialization

```java
// In ojp-server/.../OjpServer.java

public class OjpServer {
    private final QueryResultCache queryCache;
    private final SessionManager sessionManager;
    
    public OjpServer(ServerConfiguration config) {
        // Initialize cache
        int maxCacheEntries = config.getInt("ojp.cache.maxEntries", 10000);
        Duration maxCacheAge = Duration.ofHours(config.getInt("ojp.cache.maxAgeHours", 24));
        long maxCacheSizeBytes = config.getLong("ojp.cache.maxSizeBytes", 1024 * 1024 * 1024); // 1GB
        
        this.queryCache = new QueryResultCache(maxCacheEntries, maxCacheAge, maxCacheSizeBytes);
        this.sessionManager = new SessionManager(queryCache);
        
        logger.info("Query cache initialized: maxEntries={}, maxAge={}, maxSize={}MB", 
            maxCacheEntries, maxCacheAge, maxCacheSizeBytes / (1024 * 1024));
    }
    
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    public QueryResultCache getQueryCache() {
        return queryCache;
    }
}
```

### Testing

Create session integration tests:
- Test session creation with cache config
- Test cache config accessible from session
- Test session cleanup
- Test concurrent session access

### Success Criteria

- ✅ Sessions store cache configuration correctly
- ✅ Configuration accessible from actions
- ✅ Session lifecycle managed properly
- ✅ Thread-safe session access
- ✅ Integration tests pass

### Time Estimate

- Implementation: 2-3 hours
- Testing: 2-3 hours
- Integration testing: 1-2 hours
- **Total: 1 week**

---

## Phase 6: Query Execution - Cache Lookup (Week 6)

**Goal:** Check cache before executing queries on database  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~10-15 files

### Deliverables

1. Update `ExecuteQueryAction` to check cache first
2. Convert cached results to gRPC response
3. Add cache hit logging and metrics
4. Integration tests for cache hits

### Implementation

#### 6.1 Update ExecuteQueryAction

```java
// In ojp-server/.../action/ExecuteQueryAction.java

public class ExecuteQueryAction implements Action<ExecuteQueryRequest, ExecuteQueryResponse> {
    
    private final QueryResultCache cache;
    private final Logger logger = LoggerFactory.getLogger(ExecuteQueryAction.class);
    
    public ExecuteQueryAction(QueryResultCache cache) {
        this.cache = cache;
    }
    
    @Override
    public ExecuteQueryResponse execute(ExecuteQueryRequest request, ServerSession session) {
        String sql = request.getSql();
        List<Object> parameters = extractParameters(request);
        
        // Check if caching is enabled for this session
        CacheConfiguration cacheConfig = session.getCacheConfig();
        if (!cacheConfig.isEnabled()) {
            return executeQueryOnDatabase(request, session);
        }
        
        // Find matching cache rule
        CacheRule rule = cacheConfig.findMatchingRule(sql);
        if (rule == null) {
            logger.debug("No cache rule matched: sql={}", sql);
            return executeQueryOnDatabase(request, session);
        }
        
        // Try cache lookup
        QueryCacheKey cacheKey = new QueryCacheKey(
            session.getDatasourceName(),
            sql,
            parameters
        );
        
        CachedQueryResult cachedResult = cache.get(cacheKey);
        
        if (cachedResult != null) {
            // CACHE HIT
            logger.debug("Cache HIT: datasource={}, sql={}, params={}", 
                session.getDatasourceName(), sql, parameters);
            
            return buildResponseFromCache(cachedResult);
        }
        
        // CACHE MISS - will be stored in Phase 7
        logger.debug("Cache MISS: datasource={}, sql={}, params={}", 
            session.getDatasourceName(), sql, parameters);
        
        return executeQueryOnDatabase(request, session);
    }
    
    private ExecuteQueryResponse executeQueryOnDatabase(
            ExecuteQueryRequest request, 
            ServerSession session) {
        // Existing query execution logic (unchanged)
        Connection conn = getConnection(session);
        PreparedStatement stmt = conn.prepareStatement(request.getSql());
        setParameters(stmt, request);
        ResultSet rs = stmt.executeQuery();
        
        return convertResultSetToResponse(rs);
    }
    
    private ExecuteQueryResponse buildResponseFromCache(CachedQueryResult cached) {
        ExecuteQueryResponse.Builder responseBuilder = ExecuteQueryResponse.newBuilder();
        
        // Add column metadata
        for (int i = 0; i < cached.getColumnNames().size(); i++) {
            responseBuilder.addColumns(ColumnMetadata.newBuilder()
                .setName(cached.getColumnNames().get(i))
                .setType(cached.getColumnTypes().get(i))
                .build());
        }
        
        // Add rows
        for (List<Object> row : cached.getRows()) {
            RowData.Builder rowBuilder = RowData.newBuilder();
            for (Object value : row) {
                rowBuilder.addValues(convertToProtoValue(value));
            }
            responseBuilder.addRows(rowBuilder.build());
        }
        
        // Mark as cached (for client-side metrics)
        responseBuilder.setFromCache(true);  // NEW proto field
        
        return responseBuilder.build();
    }
    
    private Value convertToProtoValue(Object value) {
        if (value == null) {
            return Value.newBuilder().setNullValue(true).build();
        }
        if (value instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (value instanceof Integer i) {
            return Value.newBuilder().setIntValue(i).build();
        }
        if (value instanceof Long l) {
            return Value.newBuilder().setLongValue(l).build();
        }
        // ... more types
        return Value.newBuilder().setStringValue(value.toString()).build();
    }
    
    private List<Object> extractParameters(ExecuteQueryRequest request) {
        return request.getParametersList().stream()
            .map(this::convertFromProtoValue)
            .toList();
    }
    
    private Object convertFromProtoValue(Value value) {
        if (value.getNullValue()) return null;
        if (value.hasStringValue()) return value.getStringValue();
        if (value.hasIntValue()) return value.getIntValue();
        if (value.hasLongValue()) return value.getLongValue();
        // ... more types
        return null;
    }
}
```

#### 6.2 Update ExecuteQueryResponse Proto (Optional)

```protobuf
// In ojp-protocol/src/main/proto/query.proto

message ExecuteQueryResponse {
    repeated ColumnMetadata columns = 1;
    repeated RowData rows = 2;
    bool fromCache = 3;  // NEW - indicates result came from cache
}
```

### Testing

Create cache hit tests:
- Test cache hit returns correct data
- Test cache miss falls through to database
- Test cache key generation (datasource + SQL + params)
- Test pattern matching works correctly
- Integration test: end-to-end cache hit flow

### Success Criteria

- ✅ Cache hits return correct data
- ✅ Cache misses execute normally
- ✅ Pattern matching works for ORM queries
- ✅ No performance regression on cache misses
- ✅ Integration tests pass

### Time Estimate

- Implementation: 3-4 hours
- Testing: 2-3 hours
- Integration testing: 1-2 hours
- **Total: 1 week**

---

## Phase 7: Query Execution - Cache Storage (Week 7)

**Goal:** Store query results in cache after database execution  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~8-12 files

### Deliverables

1. Store query results in cache after execution
2. Handle large result sets (size limits)
3. Add cache storage metrics
4. Integration tests for cache population

### Implementation

#### 7.1 Complete ExecuteQueryAction

```java
// Continuing from Phase 6...

public class ExecuteQueryAction implements Action<ExecuteQueryRequest, ExecuteQueryResponse> {
    
    @Override
    public ExecuteQueryResponse execute(ExecuteQueryRequest request, ServerSession session) {
        String sql = request.getSql();
        List<Object> parameters = extractParameters(request);
        
        CacheConfiguration cacheConfig = session.getCacheConfig();
        if (!cacheConfig.isEnabled()) {
            return executeQueryOnDatabase(request, session);
        }
        
        CacheRule rule = cacheConfig.findMatchingRule(sql);
        if (rule == null) {
            return executeQueryOnDatabase(request, session);
        }
        
        QueryCacheKey cacheKey = new QueryCacheKey(
            session.getDatasourceName(),
            sql,
            parameters
        );
        
        // Check cache
        CachedQueryResult cachedResult = cache.get(cacheKey);
        if (cachedResult != null) {
            logger.debug("Cache HIT: datasource={}, sql={}", 
                session.getDatasourceName(), sql);
            return buildResponseFromCache(cachedResult);
        }
        
        // Cache MISS - execute and store
        logger.debug("Cache MISS: datasource={}, sql={}", 
            session.getDatasourceName(), sql);
        
        ExecuteQueryResponse response = executeQueryOnDatabase(request, session);
        
        // NEW: Store in cache
        try {
            CachedQueryResult resultToCache = buildCachedResult(
                response,
                rule.getTtl(),
                rule.getInvalidateOn()
            );
            
            // Check size before caching
            long resultSize = resultToCache.estimateSize();
            long maxResultSize = 200 * 1024;  // 200 KB limit
            
            if (resultSize > maxResultSize) {
                logger.debug("Result too large to cache: size={}KB, max={}KB", 
                    resultSize / 1024, maxResultSize / 1024);
            } else if (resultToCache.getRows().size() == 0) {
                logger.debug("Empty result set, not caching");
            } else {
                cache.put(cacheKey, resultToCache);
                logger.debug("Stored in cache: datasource={}, sql={}, rows={}, size={}KB", 
                    session.getDatasourceName(), sql, 
                    resultToCache.getRows().size(), resultSize / 1024);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to cache result: datasource={}, sql={}, error={}", 
                session.getDatasourceName(), sql, e.getMessage());
            // Don't fail the query if caching fails
        }
        
        return response;
    }
    
    private CachedQueryResult buildCachedResult(
            ExecuteQueryResponse response,
            Duration ttl,
            List<String> invalidateOn) {
        
        // Extract column names and types
        List<String> columnNames = response.getColumnsList().stream()
            .map(ColumnMetadata::getName)
            .toList();
        
        List<String> columnTypes = response.getColumnsList().stream()
            .map(ColumnMetadata::getType)
            .toList();
        
        // Extract rows
        List<List<Object>> rows = response.getRowsList().stream()
            .map(row -> row.getValuesList().stream()
                .map(this::convertFromProtoValue)
                .toList())
            .toList();
        
        Instant now = Instant.now();
        
        // Build affected tables set (lowercase for case-insensitive matching)
        Set<String> affectedTables = invalidateOn.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        
        return new CachedQueryResult(
            rows,
            columnNames,
            columnTypes,
            now,
            now.plus(ttl),
            affectedTables
        );
    }
}
```

### Testing

Create cache storage tests:
- Test results are cached correctly
- Test size limits are respected
- Test empty result sets not cached
- Test cache storage failures don't break queries
- Integration test: cache miss -> storage -> cache hit

### Success Criteria

- ✅ Query results cached correctly
- ✅ Size limits enforced
- ✅ Empty results not cached
- ✅ Failures don't break queries
- ✅ Integration tests pass (miss -> store -> hit)

### Time Estimate

- Implementation: 2-3 hours
- Testing: 2-3 hours
- Integration testing: 2 hours
- **Total: 1 week**

---

## Phase 8: SQL Table Extraction (Week 8)

**Goal:** Extract table names from SQL statements for invalidation  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~10-15 files

### Deliverables

1. **`SqlTableExtractor.java`** - Extract table names using JSqlParser
2. Add JSqlParser dependency
3. Handle various SQL dialects (PostgreSQL, MySQL, Oracle)
4. Comprehensive SQL parsing tests

### Implementation

#### 8.1 Add JSqlParser Dependency

```xml
<!-- In ojp-server/pom.xml -->
<dependency>
    <groupId>com.github.jsqlparser</groupId>
    <artifactId>jsqlparser</artifactId>
    <version>4.9</version>
</dependency>
```

#### 8.2 SqlTableExtractor

```java
package org.openjproxy.cache;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class SqlTableExtractor {
    
    /**
     * Extract table names from a SQL statement.
     * Returns empty set if parsing fails.
     */
    public static Set<String> extractTables(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            // Use JSqlParser's built-in table finder
            TablesNamesFinder tablesFinder = new TablesNamesFinder();
            List<String> tableNames = tablesFinder.getTableList(statement);
            
            // Normalize to lowercase for case-insensitive matching
            return tableNames.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
                
        } catch (Exception e) {
            // Parsing failed - log and return empty set
            logger.debug("Failed to parse SQL for table extraction: {}", sql, e);
            return Set.of();
        }
    }
    
    /**
     * Extract only tables being modified (INSERT, UPDATE, DELETE).
     * Returns empty set for SELECT statements.
     */
    public static Set<String> extractModifiedTables(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            if (statement instanceof Insert insert) {
                return Set.of(insert.getTable().getName().toLowerCase());
            } else if (statement instanceof Update update) {
                return Set.of(update.getTable().getName().toLowerCase());
            } else if (statement instanceof Delete delete) {
                return Set.of(delete.getTable().getName().toLowerCase());
            }
            
            // Not a DML statement
            return Set.of();
            
        } catch (Exception e) {
            logger.debug("Failed to parse SQL for modified tables: {}", sql, e);
            return Set.of();
        }
    }
    
    /**
     * Check if SQL is a write operation (INSERT, UPDATE, DELETE).
     */
    public static boolean isWriteOperation(String sql) {
        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("INSERT") 
            || trimmed.startsWith("UPDATE") 
            || trimmed.startsWith("DELETE");
    }
}
```

### Testing

Create comprehensive SQL parsing tests:
- Test SELECT statement parsing
- Test INSERT/UPDATE/DELETE parsing
- Test JOIN queries (multiple tables)
- Test subqueries
- Test various SQL dialects (PostgreSQL, MySQL, Oracle)
- Test malformed SQL (graceful failure)
- Test case sensitivity

### Example Tests

```java
@Test
public void testExtractTablesFromSelect() {
    String sql = "SELECT * FROM products WHERE category = 'electronics'";
    Set<String> tables = SqlTableExtractor.extractTables(sql);
    assertEquals(Set.of("products"), tables);
}

@Test
public void testExtractTablesFromJoin() {
    String sql = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id";
    Set<String> tables = SqlTableExtractor.extractTables(sql);
    assertEquals(Set.of("orders", "customers"), tables);
}

@Test
public void testExtractModifiedTablesFromInsert() {
    String sql = "INSERT INTO products (name, price) VALUES ('Widget', 9.99)";
    Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
    assertEquals(Set.of("products"), tables);
}

@Test
public void testMalformedSqlReturnsEmptySet() {
    String sql = "SELCT * FRM products WHERE";  // Malformed
    Set<String> tables = SqlTableExtractor.extractTables(sql);
    assertTrue(tables.isEmpty());
}
```

### Success Criteria

- ✅ Parses common SQL statements correctly
- ✅ Handles JOIN queries (multiple tables)
- ✅ Handles subqueries
- ✅ Gracefully handles malformed SQL
- ✅ Works with PostgreSQL, MySQL, Oracle dialects
- ✅ Unit tests with >95% coverage

### Time Estimate

- Add dependency: 30 minutes
- Implementation: 2-3 hours
- Testing: 3-4 hours
- Dialect testing: 1-2 hours
- **Total: 1 week**

---

## Phase 9: Write Invalidation Integration (Week 9)

**Goal:** Invalidate cache entries when tables are modified  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~10-15 files

### Deliverables

1. Update `ExecuteUpdateAction` to invalidate cache
2. Handle AUTO invalidation mode (extract tables from SQL)
3. Handle explicit invalidation (from cache rule)
4. Comprehensive invalidation tests

### Implementation

#### 9.1 Update ExecuteUpdateAction

```java
// In ojp-server/.../action/ExecuteUpdateAction.java

public class ExecuteUpdateAction implements Action<ExecuteUpdateRequest, ExecuteUpdateResponse> {
    
    private final QueryResultCache cache;
    private final Logger logger = LoggerFactory.getLogger(ExecuteUpdateAction.class);
    
    public ExecuteUpdateAction(QueryResultCache cache) {
        this.cache = cache;
    }
    
    @Override
    public ExecuteUpdateResponse execute(ExecuteUpdateRequest request, ServerSession session) {
        String sql = request.getSql();
        
        // Execute the update first
        ExecuteUpdateResponse response = executeUpdateOnDatabase(request, session);
        
        // Invalidate cache if caching is enabled
        if (session.isCachingEnabled()) {
            invalidateCache(sql, session);
        }
        
        return response;
    }
    
    private void invalidateCache(String sql, ServerSession session) {
        try {
            // Extract tables modified by this SQL
            Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(sql);
            
            if (modifiedTables.isEmpty()) {
                logger.debug("No tables extracted from SQL, skipping invalidation: sql={}", sql);
                return;
            }
            
            logger.debug("Invalidating cache: datasource={}, tables={}, sql={}", 
                session.getDatasourceName(), modifiedTables, sql);
            
            // Invalidate cache entries for this datasource and these tables
            cache.invalidate(session.getDatasourceName(), modifiedTables);
            
        } catch (Exception e) {
            logger.warn("Failed to invalidate cache: datasource={}, sql={}, error={}", 
                session.getDatasourceName(), sql, e.getMessage());
            // Don't fail the update if invalidation fails
        }
    }
    
    private ExecuteUpdateResponse executeUpdateOnDatabase(
            ExecuteUpdateRequest request, 
            ServerSession session) {
        // Existing update execution logic (unchanged)
        Connection conn = getConnection(session);
        PreparedStatement stmt = conn.prepareStatement(request.getSql());
        setParameters(stmt, request);
        int rowsAffected = stmt.executeUpdate();
        
        return ExecuteUpdateResponse.newBuilder()
            .setRowsAffected(rowsAffected)
            .build();
    }
}
```

#### 9.2 Enhanced Invalidation with AUTO Mode

If `invalidateOn` is set to `"AUTO"` in the cache rule, automatically detect tables:

```java
// In CachedQueryResult.java - add support for AUTO invalidation

public final class CachedQueryResult {
    private final Set<String> affectedTables;
    private final boolean autoInvalidation;  // NEW
    
    public CachedQueryResult(..., List<String> invalidateOn) {
        // ...
        
        if (invalidateOn.size() == 1 && "AUTO".equalsIgnoreCase(invalidateOn.get(0))) {
            // AUTO mode - extract tables from the SQL when caching
            this.autoInvalidation = true;
            this.affectedTables = Set.of();  // Will be populated from SQL
        } else {
            this.autoInvalidation = false;
            this.affectedTables = invalidateOn.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        }
    }
}

// In ExecuteQueryAction.java - extract tables when storing

private CachedQueryResult buildCachedResult(...) {
    // ...
    
    Set<String> affectedTables;
    if (invalidateOn.size() == 1 && "AUTO".equalsIgnoreCase(invalidateOn.get(0))) {
        // AUTO mode - extract tables from SQL
        affectedTables = SqlTableExtractor.extractTables(sql);
        logger.debug("AUTO invalidation: extracted tables={} from sql={}", affectedTables, sql);
    } else {
        // Explicit mode - use provided table names
        affectedTables = invalidateOn.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
    }
    
    return new CachedQueryResult(rows, columnNames, columnTypes, now, now.plus(ttl), affectedTables);
}
```

### Testing

Create comprehensive invalidation tests:
- Test INSERT invalidates correct tables
- Test UPDATE invalidates correct tables
- Test DELETE invalidates correct tables
- Test AUTO mode extracts tables correctly
- Test explicit mode uses provided tables
- Test cross-datasource isolation (don't invalidate wrong datasource)
- Integration test: cache hit -> write -> cache miss

### Example Test

```java
@Test
public void testCacheInvalidationOnUpdate() {
    // Setup
    ServerSession session = createSession("testds", cacheConfigWithProducts);
    QueryCacheKey key = new QueryCacheKey("testds", "SELECT * FROM products", List.of());
    CachedQueryResult result = createCachedResult(Set.of("products"));
    cache.put(key, result);
    
    // Execute UPDATE on products table
    ExecuteUpdateRequest updateRequest = ExecuteUpdateRequest.newBuilder()
        .setSql("UPDATE products SET price = 19.99 WHERE id = 1")
        .build();
    
    executeUpdateAction.execute(updateRequest, session);
    
    // Verify cache was invalidated
    assertNull(cache.get(key));
}

@Test
public void testCrossDataSourceIsolation() {
    // Cache entry for datasource1
    QueryCacheKey key1 = new QueryCacheKey("datasource1", "SELECT * FROM products", List.of());
    cache.put(key1, createCachedResult(Set.of("products")));
    
    // Cache entry for datasource2
    QueryCacheKey key2 = new QueryCacheKey("datasource2", "SELECT * FROM products", List.of());
    cache.put(key2, createCachedResult(Set.of("products")));
    
    // Update on datasource1
    ServerSession session1 = createSession("datasource1", cacheConfig);
    ExecuteUpdateRequest updateRequest = ExecuteUpdateRequest.newBuilder()
        .setSql("UPDATE products SET price = 19.99 WHERE id = 1")
        .build();
    executeUpdateAction.execute(updateRequest, session1);
    
    // Verify only datasource1 cache was invalidated
    assertNull(cache.get(key1));  // Invalidated
    assertNotNull(cache.get(key2));  // NOT invalidated
}
```

### Success Criteria

- ✅ DML operations invalidate cache correctly
- ✅ AUTO mode extracts and invalidates tables
- ✅ Explicit mode uses provided tables
- ✅ Cross-datasource isolation works
- ✅ Invalidation failures don't break writes
- ✅ Integration tests pass

### Time Estimate

- Implementation: 3-4 hours
- Testing: 3-4 hours
- Integration testing: 1-2 hours
- **Total: 1 week**

---

## Phase 10: Monitoring and Metrics (Week 10)

**Goal:** Add comprehensive monitoring and metrics using Micrometer  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~12-18 files

### Deliverables

1. Micrometer metrics integration
2. Cache statistics API
3. Structured logging
4. Grafana dashboard example

### Implementation

#### 10.1 Add Micrometer Dependency

```xml
<!-- In ojp-server/pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <version>1.13.0</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.13.0</version>
</dependency>
```

#### 10.2 Cache Metrics

```java
package org.openjproxy.cache;

import io.micrometer.core.instrument.*;

public class CacheMetricsReporter {
    private final MeterRegistry registry;
    private final QueryResultCache cache;
    
    public CacheMetricsReporter(MeterRegistry registry, QueryResultCache cache) {
        this.registry = registry;
        this.cache = cache;
        registerMetrics();
    }
    
    private void registerMetrics() {
        // Cache size metrics
        Gauge.builder("ojp.cache.entries", cache, QueryResultCache::getEntryCount)
            .description("Number of entries in query result cache")
            .register(registry);
        
        Gauge.builder("ojp.cache.size.bytes", cache, QueryResultCache::getCurrentSizeBytes)
            .description("Total size of cached query results in bytes")
            .baseUnit("bytes")
            .register(registry);
        
        // Cache statistics
        CacheStatistics stats = cache.getStatistics();
        
        Gauge.builder("ojp.cache.hit.rate", stats, CacheStatistics::getHitRate)
            .description("Query cache hit rate (hits / total requests)")
            .register(registry);
        
        Counter.builder("ojp.cache.hits")
            .description("Number of cache hits")
            .register(registry)
            .increment(stats.getHits());
        
        Counter.builder("ojp.cache.misses")
            .description("Number of cache misses")
            .register(registry)
            .increment(stats.getMisses());
        
        Counter.builder("ojp.cache.evictions")
            .description("Number of cache evictions")
            .register(registry)
            .increment(stats.getEvictions());
        
        Counter.builder("ojp.cache.invalidations")
            .description("Number of cache invalidations")
            .register(registry)
            .increment(stats.getInvalidations());
        
        Counter.builder("ojp.cache.rejections")
            .description("Number of cache storage rejections (too large)")
            .register(registry)
            .increment(stats.getRejections());
        
        // Query execution timings
        Timer.builder("ojp.query.execution.time")
            .description("Query execution time (cache hit or database)")
            .tag("source", "cache")  // or "database"
            .register(registry);
    }
}
```

#### 10.3 Update Actions with Metrics

```java
// In ExecuteQueryAction.java

public class ExecuteQueryAction implements Action<ExecuteQueryRequest, ExecuteQueryResponse> {
    
    private final Timer.Sample sample;
    private final MeterRegistry meterRegistry;
    
    @Override
    public ExecuteQueryResponse execute(ExecuteQueryRequest request, ServerSession session) {
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // ... cache lookup ...
            
            if (cachedResult != null) {
                // Cache HIT
                sample.stop(Timer.builder("ojp.query.execution.time")
                    .tag("source", "cache")
                    .tag("datasource", session.getDatasourceName())
                    .register(meterRegistry));
                
                return buildResponseFromCache(cachedResult);
            }
            
            // Cache MISS - execute on database
            ExecuteQueryResponse response = executeQueryOnDatabase(request, session);
            
            sample.stop(Timer.builder("ojp.query.execution.time")
                .tag("source", "database")
                .tag("datasource", session.getDatasourceName())
                .register(meterRegistry));
            
            return response;
            
        } catch (Exception e) {
            sample.stop(Timer.builder("ojp.query.execution.time")
                .tag("source", "error")
                .tag("datasource", session.getDatasourceName())
                .register(meterRegistry));
            throw e;
        }
    }
}
```

#### 10.4 Cache Statistics API

```java
// In ojp-server - add REST endpoint for cache stats

@RestController
@RequestMapping("/api/cache")
public class CacheStatsController {
    
    private final QueryResultCache cache;
    
    @GetMapping("/stats")
    public CacheStatsResponse getStats() {
        CacheStatistics stats = cache.getStatistics();
        
        return new CacheStatsResponse(
            cache.getEntryCount(),
            cache.getCurrentSizeBytes(),
            stats.getHits(),
            stats.getMisses(),
            stats.getHitRate(),
            stats.getEvictions(),
            stats.getInvalidations(),
            stats.getRejections()
        );
    }
    
    @PostMapping("/clear")
    public void clearCache() {
        cache.invalidateAll();
    }
}
```

#### 10.5 Structured Logging

```java
// Add structured logging with datasource and query info

logger.info("Cache operation completed: " +
    "datasource={}, " +
    "operation={}, " +
    "result={}, " +
    "duration={}ms, " +
    "cacheSize={}, " +
    "hitRate={}",
    datasourceName,
    operation,  // "hit", "miss", "store", "invalidate"
    result,
    duration,
    cache.getEntryCount(),
    cache.getStatistics().getHitRate()
);
```

### Testing

Create monitoring tests:
- Test metrics are registered correctly
- Test metrics are updated on cache operations
- Test cache stats API returns correct data
- Test timers measure execution correctly

### Grafana Dashboard Example

Create example Grafana dashboard JSON:
- Cache hit rate over time
- Cache size (entries and bytes)
- Query execution time (cache vs database)
- Evictions and invalidations
- Per-datasource metrics

### Success Criteria

- ✅ Micrometer metrics working
- ✅ Cache stats API functional
- ✅ Structured logging in place
- ✅ Example Grafana dashboard provided
- ✅ Metrics can be scraped by Prometheus

### Time Estimate

- Metrics implementation: 3-4 hours
- Stats API: 1-2 hours
- Logging updates: 1 hour
- Grafana dashboard: 2 hours
- Testing: 2 hours
- **Total: 1 week**

---

## Phase 11: Production Hardening (Week 11)

**Goal:** Add configuration validation, error handling, and documentation  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~10-15 files

### Deliverables

1. Configuration validation with clear error messages
2. Graceful degradation on failures
3. Security considerations (cache poisoning prevention)
4. User documentation

### Implementation

#### 11.1 Configuration Validation

```java
package org.openjproxy.cache;

public class CacheConfigurationValidator {
    
    public static ValidationResult validate(CacheConfiguration config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // Validate patterns
        for (CacheRule rule : config.getRules()) {
            try {
                rule.getPattern().pattern();  // Test pattern is valid
            } catch (PatternSyntaxException e) {
                errors.add("Invalid regex pattern: " + rule.getPattern() + " - " + e.getMessage());
            }
            
            // Validate TTL
            if (rule.getTtl().isNegative() || rule.getTtl().isZero()) {
                errors.add("TTL must be positive: " + rule.getTtl());
            }
            
            if (rule.getTtl().toSeconds() < 10) {
                warnings.add("TTL very short (< 10s): " + rule.getTtl() + " - may cause high database load");
            }
            
            if (rule.getTtl().toHours() > 24) {
                warnings.add("TTL very long (> 24h): " + rule.getTtl() + " - risk of stale data");
            }
            
            // Validate table names
            for (String table : rule.getInvalidateOn()) {
                if (table.contains(";") || table.contains("--")) {
                    errors.add("Invalid table name (potential SQL injection): " + table);
                }
            }
        }
        
        return new ValidationResult(errors, warnings);
    }
    
    public static class ValidationResult {
        private final List<String> errors;
        private final List<String> warnings;
        
        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
    }
}
```

#### 11.2 Validate on Connection

```java
// In ConnectionServiceImpl.java

public void connect(ConnectionRequest request, StreamObserver<ConnectionResponse> responseObserver) {
    try {
        CacheConfiguration cacheConfig = convertProtoCacheConfig(...);
        
        // Validate configuration
        ValidationResult validation = CacheConfigurationValidator.validate(cacheConfig);
        
        if (!validation.isValid()) {
            String errorMsg = "Invalid cache configuration: " + 
                String.join(", ", validation.getErrors());
            
            logger.error("Connection rejected due to invalid cache config: {}", errorMsg);
            
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(errorMsg)
                .asRuntimeException());
            return;
        }
        
        // Log warnings
        for (String warning : validation.getWarnings()) {
            logger.warn("Cache configuration warning: {}", warning);
        }
        
        // Proceed with connection...
        
    } catch (Exception e) {
        // ...
    }
}
```

#### 11.3 Graceful Degradation

```java
// Update ExecuteQueryAction to handle cache failures gracefully

@Override
public ExecuteQueryResponse execute(ExecuteQueryRequest request, ServerSession session) {
    try {
        // Try cache operations
        if (session.isCachingEnabled()) {
            return executeWithCache(request, session);
        }
    } catch (Exception e) {
        logger.error("Cache operation failed, falling back to database: datasource={}, sql={}, error={}", 
            session.getDatasourceName(), request.getSql(), e.getMessage(), e);
        
        // Fall through to direct database execution
    }
    
    // Direct database execution (no caching)
    return executeQueryOnDatabase(request, session);
}
```

#### 11.4 Security Considerations

```java
// Prevent cache poisoning attacks

public class CacheSecurityValidator {
    
    /**
     * Validate that cache key components don't contain SQL injection attempts.
     */
    public static boolean isSafeCacheKey(QueryCacheKey key) {
        String sql = key.getNormalizedSql();
        
        // Check for suspicious patterns
        if (sql.contains("';") || sql.contains("--") || sql.contains("/*")) {
            logger.warn("Suspicious SQL pattern in cache key: {}", sql);
            return false;
        }
        
        return true;
    }
    
    /**
     * Limit cached result size to prevent memory exhaustion attacks.
     */
    public static boolean isSafeCacheSize(CachedQueryResult result, long maxSize) {
        long size = result.estimateSize();
        return size <= maxSize;
    }
}
```

#### 11.5 User Documentation

Create comprehensive user guide:

```markdown
# Query Result Caching - User Guide

## Overview

OJP query result caching provides automatic caching of SELECT query results
to reduce database load and improve query performance.

## Configuration

Add cache configuration to your `ojp.properties` file:

```properties
# Enable caching for this datasource
postgres_prod.ojp.cache.enabled=true

# Local-only caching (no distribution)

# Cache rule 1: Product queries
postgres_prod.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
postgres_prod.ojp.cache.queries.1.ttl=600s
postgres_prod.ojp.cache.queries.1.invalidateOn=products

# Cache rule 2: User queries
postgres_prod.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE id = \?
postgres_prod.ojp.cache.queries.2.ttl=300s
postgres_prod.ojp.cache.queries.2.invalidateOn=users
```

## Pattern Syntax

Patterns use Java regex:
- `.*` matches any characters
- `\?` matches a parameter placeholder
- `\d+` matches digits
- `(electronics|clothing)` matches alternatives

## TTL Format

- `300s` - 300 seconds (5 minutes)
- `10m` - 10 minutes
- `1h` - 1 hour

## Invalidation

Cache entries are automatically invalidated when tables are modified:
- INSERT, UPDATE, DELETE on specified tables
- AUTO mode: automatically detect tables from query

## Monitoring

Check cache statistics:
- Prometheus metrics at `/metrics`
- Cache stats API at `/api/cache/stats`
- Grafana dashboard (see `grafana-dashboard.json`)

## Troubleshooting

### Cache not working
- Check `ojp.cache.enabled=true`
- Verify pattern matches your SQL
- Check logs for "Cache HIT" / "Cache MISS"

### High cache miss rate
- Patterns too specific
- TTL too short
- Queries have varying parameters

### High memory usage
- Reduce `maxEntries` in server config
- Lower TTL values
- Cache smaller result sets
```

### Testing

Create hardening tests:
- Test configuration validation (invalid patterns, bad TTLs)
- Test graceful degradation (cache failures)
- Test security validation (SQL injection attempts)
- Test error messages are clear and actionable

### Success Criteria

- ✅ Configuration validation works
- ✅ Clear error messages provided
- ✅ Graceful degradation on failures
- ✅ Security considerations addressed
- ✅ User documentation complete

### Time Estimate

- Validation implementation: 2-3 hours
- Graceful degradation: 1-2 hours
- Security hardening: 1-2 hours
- Documentation: 2-3 hours
- Testing: 1-2 hours
- **Total: 1 week**

---

## Phase 12: Unit and Integration Tests (Week 12)

**Goal:** Comprehensive test coverage for all caching functionality  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~15-20 files

### Deliverables

1. Unit tests for all cache classes (>95% coverage)
2. Integration tests for cache flow
3. Concurrency tests
4. Performance benchmarks

### Test Categories

#### 12.1 Unit Tests

Create comprehensive unit tests:
- `QueryCacheKeyTest` - Equality, hashing, normalization
- `CachedQueryResultTest` - Expiration, size estimation
- `CacheRuleTest` - Pattern matching, TTL parsing
- `CacheConfigurationTest` - Rule ordering, matching
- `CacheConfigurationParserTest` - Property parsing
- `QueryResultCacheTest` - Cache operations
- `CacheStatisticsTest` - Metrics tracking
- `SqlTableExtractorTest` - SQL parsing
- `CacheConfigurationValidatorTest` - Validation logic

#### 12.2 Integration Tests

Create end-to-end integration tests:

```java
@SpringBootTest
public class CacheIntegrationTest {
    
    @Test
    public void testCacheHitFlow() {
        // 1. Configure cache
        setSystemProperty("testds.ojp.cache.enabled", "true");
        setSystemProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
        setSystemProperty("testds.ojp.cache.queries.1.ttl", "600s");
        
        // 2. Connect (sends cache config to server)
        Connection conn = DriverManager.getConnection("jdbc:ojp[localhost:1059(testds)]_...");
        
        // 3. Execute query (cache MISS)
        Statement stmt = conn.createStatement();
        ResultSet rs1 = stmt.executeQuery("SELECT * FROM products WHERE id = 1");
        assertNotNull(rs1);
        
        // 4. Execute same query (cache HIT)
        ResultSet rs2 = stmt.executeQuery("SELECT * FROM products WHERE id = 1");
        assertNotNull(rs2);
        
        // 5. Verify cache hit (check metrics or logs)
        CacheStatistics stats = getServerCacheStatistics();
        assertEquals(1, stats.getHits());
        assertEquals(1, stats.getMisses());
    }
    
    @Test
    public void testCacheInvalidationOnUpdate() {
        // 1. Query and cache result
        ResultSet rs1 = stmt.executeQuery("SELECT * FROM products WHERE id = 1");
        
        // 2. Update the table
        stmt.executeUpdate("UPDATE products SET price = 19.99 WHERE id = 1");
        
        // 3. Query again (should be cache MISS due to invalidation)
        ResultSet rs2 = stmt.executeQuery("SELECT * FROM products WHERE id = 1");
        
        // 4. Verify invalidation happened
        CacheStatistics stats = getServerCacheStatistics();
        assertEquals(0, stats.getHits());  // Both queries were misses
        assertEquals(2, stats.getMisses());
        assertEquals(1, stats.getInvalidations());
    }
    
    @Test
    public void testMultipleDatasourceIsolation() {
        // Connect to two different datasources
        Connection conn1 = DriverManager.getConnection("jdbc:ojp[localhost:1059(datasource1)]_...");
        Connection conn2 = DriverManager.getConnection("jdbc:ojp[localhost:1059(datasource2)]_...");
        
        // Query both
        Statement stmt1 = conn1.createStatement();
        stmt1.executeQuery("SELECT * FROM products");
        
        Statement stmt2 = conn2.createStatement();
        stmt2.executeQuery("SELECT * FROM products");
        
        // Update datasource1
        stmt1.executeUpdate("UPDATE products SET price = 19.99");
        
        // Query both again
        stmt1.executeQuery("SELECT * FROM products");  // Should be MISS (invalidated)
        stmt2.executeQuery("SELECT * FROM products");  // Should be HIT (not invalidated)
    }
}
```

#### 12.3 Concurrency Tests

Test thread safety:

```java
@Test
public void testConcurrentCacheAccess() throws Exception {
    int threadCount = 10;
    int operationsPerThread = 1000;
    
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                for (int j = 0; j < operationsPerThread; j++) {
                    // Random cache operations
                    if (j % 3 == 0) {
                        cache.get(randomKey());
                    } else if (j % 3 == 1) {
                        cache.put(randomKey(), randomResult());
                    } else {
                        cache.invalidate("testds", Set.of("products"));
                    }
                }
            } finally {
                latch.countDown();
            }
        });
    }
    
    assertTrue(latch.await(30, TimeUnit.SECONDS));
    
    // Verify cache is still consistent
    assertTrue(cache.getEntryCount() >= 0);
    assertTrue(cache.getCurrentSizeBytes() >= 0);
}
```

#### 12.4 Performance Benchmarks

Measure cache performance:

```java
@Test
public void benchmarkCacheHitPerformance() {
    // Warm up
    for (int i = 0; i < 1000; i++) {
        cache.get(testKey);
    }
    
    // Benchmark
    long start = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
        cache.get(testKey);
    }
    long duration = System.nanoTime() - start;
    
    double avgLatencyMs = duration / 10000.0 / 1_000_000;
    
    System.out.println("Average cache hit latency: " + avgLatencyMs + "ms");
    assertTrue(avgLatencyMs < 1.0, "Cache hit should be < 1ms");
}

@Test
public void benchmarkCacheMissVsDatabaseQuery() {
    // Benchmark cache miss (includes database query)
    long cacheMissTime = measureQueryTime(() -> {
        // Execute query that's not cached
        stmt.executeQuery("SELECT * FROM products WHERE id = " + UUID.randomUUID());
    });
    
    // Benchmark cache hit
    stmt.executeQuery("SELECT * FROM products WHERE id = 1");  // Cache it
    long cacheHitTime = measureQueryTime(() -> {
        stmt.executeQuery("SELECT * FROM products WHERE id = 1");  // Hit cache
    });
    
    System.out.println("Cache miss time: " + cacheMissTime + "ms");
    System.out.println("Cache hit time: " + cacheHitTime + "ms");
    System.out.println("Speedup: " + (cacheMissTime / (double) cacheHitTime) + "x");
    
    assertTrue(cacheHitTime < cacheMissTime / 5, "Cache hit should be at least 5x faster");
}
```

### Success Criteria

- ✅ Unit test coverage >95%
- ✅ Integration tests pass
- ✅ Concurrency tests pass (no race conditions)
- ✅ Performance benchmarks show expected speedup
- ✅ All tests green in CI

### Time Estimate

- Unit tests: 3-4 hours
- Integration tests: 2-3 hours
- Concurrency tests: 1-2 hours
- Performance benchmarks: 1-2 hours
- **Total: 1 week**

---

## Phase 13: E2E and Performance Testing (Week 13)

**Goal:** Test with real ORMs and realistic workloads  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Single 3-4 hour session  
**Files Changed:** ~12-18 files

### Deliverables

1. Hibernate integration tests
2. Spring Data JPA integration tests
3. MyBatis integration tests
4. Realistic workload performance tests
5. Load testing

### Implementation

#### 13.1 Hibernate Integration Test

```java
@SpringBootTest
public class HibernateCachingTest {
    
    @Autowired
    private EntityManager entityManager;
    
    @Test
    public void testHibernateQueryCaching() {
        // Configure cache for Hibernate queries
        setSystemProperty("testds.ojp.cache.enabled", "true");
        setSystemProperty("testds.ojp.cache.queries.1.pattern", "select .* from Product.*");
        setSystemProperty("testds.ojp.cache.queries.1.ttl", "600s");
        
        // First query (cache MISS)
        List<Product> products1 = entityManager
            .createQuery("SELECT p FROM Product p WHERE p.category = :category", Product.class)
            .setParameter("category", "electronics")
            .getResultList();
        
        // Second query (cache HIT)
        List<Product> products2 = entityManager
            .createQuery("SELECT p FROM Product p WHERE p.category = :category", Product.class)
            .setParameter("category", "electronics")
            .getResultList();
        
        // Verify same data returned
        assertEquals(products1.size(), products2.size());
        
        // Verify cache was hit
        CacheStatistics stats = getServerCacheStatistics();
        assertEquals(1, stats.getHits());
    }
}
```

#### 13.2 Spring Data JPA Integration Test

```java
@SpringBootTest
public class SpringDataJpaCachingTest {
    
    @Autowired
    private ProductRepository productRepository;
    
    @Test
    public void testSpringDataQueryCaching() {
        // Configure cache for Spring Data queries
        setSystemProperty("testds.ojp.cache.enabled", "true");
        setSystemProperty("testds.ojp.cache.queries.1.pattern", "select .* from products.*");
        setSystemProperty("testds.ojp.cache.queries.1.ttl", "600s");
        
        // First query (cache MISS)
        List<Product> products1 = productRepository.findByCategory("electronics");
        
        // Second query (cache HIT)
        List<Product> products2 = productRepository.findByCategory("electronics");
        
        // Verify cache was hit
        assertEquals(products1.size(), products2.size());
    }
    
    @Test
    public void testCacheInvalidationOnSave() {
        // Query and cache
        List<Product> products1 = productRepository.findByCategory("electronics");
        
        // Save a product (triggers invalidation)
        Product product = new Product();
        product.setName("New Widget");
        product.setCategory("electronics");
        productRepository.save(product);
        
        // Query again (should be cache MISS)
        List<Product> products2 = productRepository.findByCategory("electronics");
        
        // Verify invalidation
        assertEquals(products1.size() + 1, products2.size());
    }
}
```

#### 13.3 MyBatis Integration Test

```java
@SpringBootTest
public class MyBatisCachingTest {
    
    @Autowired
    private ProductMapper productMapper;
    
    @Test
    public void testMyBatisQueryCaching() {
        // Configure cache
        setSystemProperty("testds.ojp.cache.enabled", "true");
        setSystemProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
        setSystemProperty("testds.ojp.cache.queries.1.ttl", "600s");
        
        // First query (cache MISS)
        List<Product> products1 = productMapper.selectByCategory("electronics");
        
        // Second query (cache HIT)
        List<Product> products2 = productMapper.selectByCategory("electronics");
        
        // Verify cache hit
        assertEquals(products1.size(), products2.size());
    }
}
```

#### 13.4 Realistic Workload Test

```java
@Test
public void testRealisticEcommerceWorkload() {
    // Simulate e-commerce workload:
    // - 70% reads (product catalog)
    // - 20% reads (user data)
    // - 10% writes (orders, updates)
    
    int totalRequests = 10000;
    int productReads = 7000;
    int userReads = 2000;
    int writes = 1000;
    
    Random random = new Random();
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < totalRequests; i++) {
        if (i < productReads) {
            // Product catalog read
            int productId = random.nextInt(100) + 1;
            stmt.executeQuery("SELECT * FROM products WHERE id = " + productId);
            
        } else if (i < productReads + userReads) {
            // User data read
            int userId = random.nextInt(1000) + 1;
            stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
            
        } else {
            // Write operation
            int orderId = random.nextInt(10000) + 1;
            stmt.executeUpdate("UPDATE orders SET status = 'shipped' WHERE id = " + orderId);
        }
    }
    
    long duration = System.currentTimeMillis() - startTime;
    
    // Analyze results
    CacheStatistics stats = getServerCacheStatistics();
    double hitRate = stats.getHitRate();
    double avgLatency = duration / (double) totalRequests;
    
    System.out.println("Workload completed:");
    System.out.println("- Total requests: " + totalRequests);
    System.out.println("- Duration: " + duration + "ms");
    System.out.println("- Avg latency: " + avgLatency + "ms");
    System.out.println("- Cache hit rate: " + (hitRate * 100) + "%");
    System.out.println("- Cache hits: " + stats.getHits());
    System.out.println("- Cache misses: " + stats.getMisses());
    
    // Assert performance targets
    assertTrue(hitRate > 0.60, "Hit rate should be > 60%");
    assertTrue(avgLatency < 50, "Avg latency should be < 50ms");
}
```

#### 13.5 Load Testing

```java
@Test
public void testConcurrentLoad() throws Exception {
    // Simulate 100 concurrent users
    int userCount = 100;
    int requestsPerUser = 100;
    
    ExecutorService executor = Executors.newFixedThreadPool(userCount);
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < userCount; i++) {
        final int userId = i;
        executor.submit(() -> {
            try (Connection conn = createConnection()) {
                Statement stmt = conn.createStatement();
                
                for (int j = 0; j < requestsPerUser; j++) {
                    try {
                        // Mix of cached and non-cached queries
                        if (j % 2 == 0) {
                            stmt.executeQuery("SELECT * FROM products WHERE category = 'electronics'");
                        } else {
                            stmt.executeQuery("SELECT * FROM users WHERE id = " + userId);
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });
    }
    
    assertTrue(latch.await(5, TimeUnit.MINUTES));
    executor.shutdown();
    
    long duration = System.currentTimeMillis() - startTime;
    int totalRequests = userCount * requestsPerUser;
    
    System.out.println("Load test completed:");
    System.out.println("- Users: " + userCount);
    System.out.println("- Requests/user: " + requestsPerUser);
    System.out.println("- Total requests: " + totalRequests);
    System.out.println("- Success: " + successCount.get());
    System.out.println("- Errors: " + errorCount.get());
    System.out.println("- Duration: " + duration + "ms");
    System.out.println("- Throughput: " + (totalRequests * 1000.0 / duration) + " req/s");
    
    // Assert no errors and reasonable throughput
    assertEquals(totalRequests, successCount.get());
    assertEquals(0, errorCount.get());
    assertTrue(totalRequests * 1000.0 / duration > 100, "Throughput should be > 100 req/s");
}
```

### Success Criteria

- ✅ Hibernate integration works
- ✅ Spring Data JPA integration works
- ✅ MyBatis integration works
- ✅ Realistic workload achieves >60% hit rate
- ✅ Load test shows no errors under 100 concurrent users
- ✅ Performance targets met (latency, throughput)

### Time Estimate

- ORM integration tests: 3-4 hours
- Realistic workload test: 2-3 hours
- Load testing: 2-3 hours
- Analysis and optimization: 1-2 hours
- **Total: 1 week**

---

## Phase 14: Production Deployment (Week 14)

**Goal:** Deploy to production with gradual rollout and monitoring  
**Duration:** 1 week (40 hours)  
**Copilot Session:** Configuration and monitoring (no code changes)

### Deliverables

1. Pilot deployment plan
2. Production configuration
3. Monitoring dashboards
4. Rollout strategy
5. Post-deployment review

### Implementation

#### 14.1 Pilot Deployment

**Select pilot application:**
- Low-risk application with known query patterns
- Good monitoring in place
- Team available for quick rollback

**Configuration:**
```properties
# Pilot app: analytics-api
analytics_db.ojp.cache.enabled=true

# Conservative cache rules
analytics_db.ojp.cache.queries.1.pattern=SELECT .* FROM reports WHERE .*
analytics_db.ojp.cache.queries.1.ttl=300s  # 5 minutes
analytics_db.ojp.cache.queries.1.invalidateOn=reports

analytics_db.ojp.cache.queries.2.pattern=SELECT .* FROM dashboards WHERE .*
analytics_db.ojp.cache.queries.2.ttl=600s  # 10 minutes
analytics_db.ojp.cache.queries.2.invalidateOn=dashboards
```

**Deployment steps:**
1. Deploy updated OJP server with caching support
2. Update pilot application with cache configuration
3. Deploy application
4. Monitor for 48 hours

#### 14.2 Monitoring Setup

**Grafana Dashboard:**
- Cache hit rate over time
- Cache size (entries and bytes)
- Query latency (P50, P95, P99)
- Cache operations (hits, misses, evictions, invalidations)
- Per-datasource metrics
- Database load (queries/second)

**Alerts:**
- Cache hit rate < 40% (tuning needed)
- Cache size > 80% of max (risk of eviction thrashing)
- Query latency P95 increased > 20% (performance regression)
- Error rate > 1% (potential issues)

#### 14.3 Gradual Rollout

**Week 1 (Pilot - 10% of applications):**
- Deploy to 1-2 pilot applications
- Monitor closely (hourly checks)
- Gather feedback from teams
- Tune cache rules if needed

**Week 2 (50% rollout):**
- Deploy to 50% of applications
- Reduce monitoring frequency (daily checks)
- Continue gathering feedback
- Document best practices

**Week 3 (100% rollout):**
- Deploy to all applications
- Standard monitoring (weekly reviews)
- Team training and knowledge sharing
- Post-deployment review

#### 14.4 Rollback Plan

If issues occur:

1. **Immediate rollback** (< 5 minutes):
   ```properties
   # Disable caching
   *.ojp.cache.enabled=false
   ```
   Restart application - caching disabled

2. **Targeted rollback** (if specific app has issues):
   ```properties
   # Disable for specific datasource
   problematic_ds.ojp.cache.enabled=false
   ```

3. **Full OJP server rollback**:
   - Redeploy previous OJP server version
   - All apps continue working (backwards compatible)

#### 14.5 Post-Deployment Review

**Metrics to review:**
- Cache hit rate: Target >60%
- Query latency reduction: Target >30%
- Database load reduction: Target >40%
- Error rate: Target 0%
- Stale data incidents: Target 0

**Questions:**
- Did we meet performance targets?
- Were there any incidents?
- What cache rules work best?
- What tuning is needed?
- Should we implement distributed caching (Phase 8)?

#### 14.6 Team Training

**Training materials:**
- User guide (from Phase 11)
- Best practices guide
- Troubleshooting guide
- Monitoring guide

**Training sessions:**
- Overview of caching architecture
- How to configure cache rules
- How to monitor cache performance
- How to troubleshoot issues

### Success Criteria

- ✅ Pilot deployment successful (no incidents)
- ✅ 100% rollout completed
- ✅ Performance targets met
- ✅ Zero stale data incidents
- ✅ Team trained and confident

### Time Estimate

- Pilot deployment: 1-2 days
- Monitoring setup: 1 day
- Gradual rollout: 2-3 days
- Post-deployment review: 1 day
- Team training: 1 day
- **Total: 1 week**

---

## Summary

### Total Timeline

**14 weeks (3.5 months)** for production-ready local caching

| Phase | Week | Focus | Copilot Session |
|-------|------|-------|-----------------|
| 1 | 1 | Core data structures | ✅ Single session |
| 2 | 2 | Configuration parsing | ✅ Single session |
| 3 | 3 | Cache storage | ✅ Single session |
| 4 | 4 | Protocol updates | ✅ Single session |
| 5 | 5 | Session integration | ✅ Single session |
| 6 | 6 | Query execution - lookup | ✅ Single session |
| 7 | 7 | Query execution - storage | ✅ Single session |
| 8 | 8 | SQL table extraction | ✅ Single session |
| 9 | 9 | Write invalidation | ✅ Single session |
| 10 | 10 | Monitoring & metrics | ✅ Single session |
| 11 | 11 | Production hardening | ✅ Single session |
| 12 | 12 | Unit & integration tests | ✅ Single session |
| 13 | 13 | E2E & performance tests | ✅ Single session |
| 14 | 14 | Production deployment | ✅ Config only |

### Resource Requirements

**Team:**
- 1 Senior Java Developer (full-time) - 560 hours
- 1 DevOps Engineer (part-time) - 280 hours
- 1 QA Engineer (part-time) - 280 hours
- 1 Tech Lead (oversight) - 140 hours

**Total Effort:** ~1,260 hours (~7.9 person-months)

### Success Metrics

**Performance:**
- Cache hit rate >60%
- Query latency reduced >30%
- Database load reduced >40%

**Operational:**
- Zero stale data incidents
- Graceful degradation on failures
- Deployment without downtime

**Business:**
- Database costs reduced 20-40%
- ROI positive within 3 months

### Next Steps After Phase 14

**Phase 15+ (Future - Optional):**
- Distributed cache coordination (JDBC driver relay)
- Redis integration for very large clusters
- Cache warming/preloading
- Semantic query analysis
- Advanced compression

**Decision Point:** Evaluate need for distributed caching based on:
- Multiple OJP servers with shared query patterns?
- High cache miss rate due to server-specific caching?
- Clear ROI for distributed coordination?

---

## Key Advantages of Session-Sized Phases

**For Copilot:**
- ✅ Each phase fits comfortably in 2-4 hour session
- ✅ Clear scope and deliverables per phase
- ✅ Minimal context switching between phases
- ✅ Testable increments

**For Team:**
- ✅ Weekly progress milestones
- ✅ Early value delivery (Phase 7 has basic caching working)
- ✅ Quality gates between phases
- ✅ Easier to track and report progress

**For Project:**
- ✅ Lower risk (smaller increments)
- ✅ Easier to pause/resume
- ✅ Can adjust priorities between phases
- ✅ Clear decision points

---

This completes the session-sized phased implementation plan for query result caching in OJP!

# Refactoring Cache Logic to Action-Based Architecture

## Overview

This document explains how the cache implementation (Phases 6-9) was adapted to work with the refactored action-based architecture introduced in main branch commit `045e723`.

## Background

### Original Architecture (Our Branch)
- Cache logic implemented directly in `StatementServiceImpl`
- `executeQueryInternal()` method contained both cache lookup and database execution
- `CachingStreamObserver` was an inner class of `StatementServiceImpl`
- Cache invalidation was in `ExecuteUpdateAction` (already separated)

### New Architecture (Main Branch)
- Query execution refactored to `ExecuteQueryAction` class
- Update execution remains in `ExecuteUpdateAction` class  
- `CommandExecutionHelper` provides resilience/retry logic
- `StatementServiceImpl` now just delegates to actions

## Migration Strategy

### 1. Merge Resolution
- Merged main branch into our feature branch
- Resolved conflicts by accepting main's structure
- Identified code movement: `StatementServiceImpl.executeQueryInternal()` → `ExecuteQueryAction.executeQueryInternal()`

### 2. Cache Logic Relocation

#### Phase 6: Cache Lookup
**From:** `StatementServiceImpl.executeQueryInternal()` (lines 216-261)  
**To:** `ExecuteQueryAction.executeQueryInternal()` (lines 64-112)

**Logic:**
1. Get session cache configuration
2. Match SQL against cache rules
3. Build cache key (datasource + SQL + params)
4. Query cache
5. On HIT: return cached result, skip database
6. On MISS: continue to database execution

#### Phase 7: Cache Storage
**From:** `StatementServiceImpl.CachingStreamObserver` (inner class, ~140 lines)  
**To:** `ExecuteQueryAction.CachingStreamObserver` (inner class, lines 231-358)

**Logic:**
1. Wrap `StreamObserver` to intercept results
2. Forward all results to client immediately
3. Capture first `OpResult` for caching
4. On `onCompleted()`, store in cache if eligible
5. Size validation, proto conversion, error handling

#### Phase 9: Write Invalidation
**Already in:** `ExecuteUpdateAction.executeUpdateInternal()`  
**No relocation needed** - just ensured it remained functional after merge

**Added:** `invalidateCacheIfEnabled()` method (lines 273-311)

### 3. Constructor Updates

#### StatementServiceImpl Constructor
**Before:**
```java
public StatementServiceImpl(SessionManager sessionManager, 
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           ServerConfiguration serverConfiguration)
```

**After:**
```java
public StatementServiceImpl(SessionManager sessionManager,
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           ServerConfiguration serverConfiguration,
                           Map<String, CacheConfiguration> cacheConfigurationMap)
```

**Changes:**
- Added 4th parameter for cache configuration map
- Passed to `ActionContext` constructor (line 92)
- Maintains backward compatibility via null check

#### ActionContext Constructor
**Already Updated** by main branch merge:
- Includes `cacheConfigurationMap` parameter (line 150)
- Stores and provides getter (line 202)

### 4. Integration Points

| Component | Integration Point | Purpose |
|-----------|------------------|---------|
| `ExecuteQueryAction` | `executeQueryInternal()` start | Cache lookup before DB |
| `ExecuteQueryAction` | `executeQueryInternal()` middle | Wrap observer for storage |
| `ExecuteQueryAction` | `CachingStreamObserver` class | Intercept and store results |
| `ExecuteUpdateAction` | `executeUpdateInternal()` end | Invalidate after update |
| `ActionContext` | Constructor + getter | Share cache config across actions |

## Code Flow Diagrams

### Query Execution with Cache (Phase 6 & 7)

```
ExecuteQueryAction.execute()
  ↓
executeQueryInternal()
  ↓
[Phase 6: Cache Lookup]
  ├─ Check session.getCacheConfiguration()
  ├─ Match SQL pattern
  ├─ Build cache key
  ├─ Query cache
  └─ If HIT: return cached → END
  ↓
[SQL Enhancement]
  ↓
[Phase 7: Wrap Observer]
  ├─ Create CachingStreamObserver
  └─ Wrap responseObserver
  ↓
[Database Execution]
  ├─ ps.executeQuery() or stmt.executeQuery()
  └─ handleResultSet(finalObserver)
      ↓
[CachingStreamObserver]
  ├─ onNext() → forward + capture
  └─ onCompleted() → store in cache
```

### Update Execution with Invalidation (Phase 9)

```
ExecuteUpdateAction.execute()
  ↓
executeUpdateInternal()
  ↓
[Database Execution]
  ├─ ps.executeUpdate() or stmt.executeUpdate()
  ↓
[Build Result]
  ↓
[Phase 9: Cache Invalidation]
  ├─ invalidateCacheIfEnabled()
  ├─ Extract modified tables (SqlTableExtractor)
  ├─ Get cache for datasource
  └─ cache.invalidate(datasource, tables)
  ↓
[Return Result]
```

## Testing Implications

### Unaffected Tests
- **Phase 1-2:** Core data structures and configuration parsing
- **Phase 3:** Cache storage (Caffeine integration)
- **Phase 4:** Protocol updates (properties-based)
- **Phase 5:** Session integration
- **Phase 8:** SQL table extraction (utilities)
- **Phase 9:** Write invalidation (already in actions)

### Tests Requiring Updates
- **Phase 6:** `QueryCacheLookupIntegrationTest`
  - Now needs to test `ExecuteQueryAction` instead of `StatementServiceImpl`
  - Mock/setup ActionContext appropriately
  
- **Phase 7:** `QueryCacheStorageIntegrationTest`
  - Now needs to test `ExecuteQueryAction` with observer wrapping
  - Verify `CachingStreamObserver` behavior

## Benefits of New Structure

### 1. **Separation of Concerns**
- Cache logic isolated within action classes
- Clear responsibility: actions handle their own caching
- `StatementServiceImpl` remains thin (delegation only)

### 2. **Maintainability**
- Cache code next to execution logic (easier to understand)
- Changes to query execution naturally include cache considerations
- Follows single responsibility principle

### 3. **Testability**
- Actions can be tested independently
- Mock `ActionContext` for isolated cache testing
- Clear boundaries for unit vs integration tests

### 4. **Consistency**
- Follows existing OJP architecture patterns
- All execution logic in action classes
- Shared state via `ActionContext`

## Implementation Checklist

- [x] Merge main branch (commit 045e723)
- [x] Update `StatementServiceImpl` constructor
- [x] Move Phase 6 (cache lookup) to `ExecuteQueryAction`
- [x] Move Phase 7 (cache storage) to `ExecuteQueryAction`
- [x] Verify Phase 9 (cache invalidation) in `ExecuteUpdateAction`
- [x] Ensure `ActionContext` has cache configuration map
- [x] Commit and push changes
- [ ] Update Phase 6 integration tests
- [ ] Update Phase 7 integration tests
- [ ] Verify all 248 tests pass
- [ ] Document test updates

## Next Steps

1. **Update Integration Tests**
   - Modify `QueryCacheLookupIntegrationTest` for action-based testing
   - Modify `QueryCacheStorageIntegrationTest` for action-based testing
   - Ensure proper `ActionContext` setup in tests

2. **Verify Compilation**
   - Fix Java 21 target issue in `ojp-xa-pool-commons` (unrelated)
   - Run full Maven build with tests
   - Verify no regression in existing functionality

3. **Proceed to Phase 10**
   - Monitoring & Metrics (Micrometer integration)
   - Export cache statistics to Prometheus
   - Add JMX MBeans for runtime inspection

## References

- **Main Branch Refactoring:** Commit `045e723` - "Refactor StatementServiceImpl and introduce CommandExecutionHelper"
- **Our Merge Commit:** Commit `22409ba` - "Merge main and adapt cache logic to refactored action classes"
- **Architecture Documentation:** `documents/architecture/` (OJP documentation)
- **Phase Implementation Plans:** `CACHING_IMPLEMENTATION_PLAN_SESSIONIZED.md`

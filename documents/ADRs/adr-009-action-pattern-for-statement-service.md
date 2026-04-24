# ADR 009: Action Pattern for StatementServiceImpl

In the context of the OJP project,  
facing a 2,528-line `StatementServiceImpl` God class with 21 public methods that made testing, code review, and parallel development difficult,  

we decided for decomposing `StatementServiceImpl` into stateless, singleton **Action classes** each responsible for exactly one gRPC operation, coordinated by a shared **ActionContext** data-holder,  
and neglected keeping all logic inline in `StatementServiceImpl`, or restructuring it through inheritance or a dependency-injection framework,  

to achieve independent testability, focused code reviews, and the ability for multiple contributors to work on separate actions simultaneously without merge conflicts,  
accepting a larger number of source files and the need for contributors to follow the singleton–stateless contract,  

because stateless singletons carry zero per-request allocation overhead, are naturally thread-safe, and align with OJP's goal of keeping the hot path free from GC pressure.

## Context

`StatementServiceImpl` is the central gRPC service implementation that handles all SQL operations proxied by OJP.  After several months of active development it grew to 2,528 lines with 21 public methods covering connection management, SQL execution, transaction control, XA distributed transactions, and LOB streaming.

The single-class structure created several concrete problems:

1. **Untestable in isolation**: Every unit test required wiring the full class with all its dependencies.
2. **Merge conflicts**: Concurrent PRs editing the same file conflicted constantly.
3. **Cognitive load**: Reviewers had to context-switch across unrelated logic within the same file.
4. **No clear extension point**: Adding support for a new operation required understanding the full class.

## Decision

We introduced the **Action Pattern** to decompose `StatementServiceImpl` into focused classes:

- **`StatementServiceImpl`** becomes a thin orchestrator (~150 lines) that creates and holds an `ActionContext` and delegates each gRPC method to the corresponding singleton Action class.
- **`ActionContext`** is a thread-safe data-holder (all maps are `ConcurrentHashMap`) that carries all shared state: datasource maps, `SessionManager`, `CircuitBreakerRegistry`, `ServerConfiguration`, XA registries, and others. It is created once at server startup.
- **Action interfaces** define four contracts, chosen to cover every method shape present in the gRPC service:

| Interface | Signature | Used for |
|---|---|---|
| `Action<TRequest, TResponse>` | `void execute(ActionContext, TRequest, StreamObserver<TResponse>)` | Standard unary / server-streaming RPCs (most methods) |
| `StreamingAction<TRequest, TResponse>` | `StreamObserver<TRequest> execute(ActionContext, StreamObserver<TResponse>)` | Bidirectional streaming (createLob) |
| `InitAction` | `void execute()` | One-time server initialisation (e.g., XA pool provider setup) |
| `ValueAction<TRequest, TResult>` | `TResult execute(TRequest) throws Exception` | Internal helper operations that return a value directly rather than via `StreamObserver` |

- **All Action implementations** MUST be singletons (private constructor, static `INSTANCE` field, `getInstance()` method) and MUST be stateless (no mutable instance fields). All per-request state is passed via the method parameters.

The migration was executed incrementally across PRs #214 and #261–#284 in January 2026, with each PR migrating one or more methods, ensuring continuous integration throughout.

## Consequences

### Positive

1. **Independent testability**: Each Action can be unit-tested with a minimal `ActionContext` mock containing only the fields that particular action needs.
2. **Parallel development**: Multiple contributors can work on separate Action classes simultaneously with zero file-level merge conflicts in `StatementServiceImpl`.
3. **Focused code review**: Each PR touches one Action class (~75–200 lines), making reviews fast and precise.
4. **Zero allocation overhead**: Singletons eliminate per-request object creation on the hot path; GC pressure is unchanged.
5. **Explicit dependency graph**: An Action's dependencies are visible from the subset of `ActionContext` fields it reads, rather than buried in a 2,500-line class.
6. **Consistent structure**: Every contributor follows the same singleton–stateless template, reducing onboarding time.

### Negative

1. **More source files**: 30+ Action classes instead of 1 class; IDE navigation requires knowing the class name.
2. **Singleton contract**: Contributors must understand and follow the stateless-singleton requirement; violating it (storing mutable state as an instance field) would introduce subtle concurrency bugs.
3. **`ActionContext` growth**: All shared state passes through one object; if it accumulates unnecessary fields it can become its own form of God object.

### Mitigations

1. **Contributor guide**: `documents/designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md` documents the pattern, all four interface types, singleton rationale, and step-by-step implementation instructions with code examples.
2. **Javadoc on all interfaces**: `Action`, `StreamingAction`, `InitAction`, and `ValueAction` all carry Javadoc explaining the singleton requirement and thread-safety contract.
3. **`ActionContext` discipline**: Fields are added to `ActionContext` only when genuinely shared across multiple Actions; Action-local state stays local to the method.
4. **Reference implementation**: `ConnectAction` (PR #214) serves as the canonical example for all contributors.

## Alternatives Considered

### 1. Keep logic in `StatementServiceImpl` with private helper methods
**Rejected**: Reduces line count superficially but keeps all logic in one compilation unit, preserving merge-conflict and testability problems.

### 2. Inheritance (abstract base class per operation group)
**Rejected**: Java's single-inheritance constraint limits flexibility; deep hierarchies obscure control flow; harder to test leaf classes in isolation.

### 3. Dependency injection (Spring, Guice) to inject per-operation beans
**Rejected**: OJP Server's startup is not Spring-managed; adding DI solely for this concern would introduce a heavyweight dependency for a problem already solved by the stateless-singleton pattern.

### 4. Command pattern with a `Map<MethodName, Command>` dispatch table
**Rejected**: Dynamic dispatch loses type safety on request/response types and makes IDE navigation harder; the direct delegation model (`ConnectAction.getInstance().execute(...)`) is simpler and produces better stack traces.

## Related Decisions

- **ADR-006**: SPI pattern — the same "stateless, discoverable handler" philosophy applied at the pool-provider level.
- **ADR-004**: Implement JDBC interfaces — established OJP's commitment to clear interface contracts.

## References

- [Action Pattern Migration Guide](../designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md) — contributor how-to with code examples
- [Architecture Chapter 2, Section 2.5](../ebook/part1-chapter2-architecture.md) — high-level overview of the Action Pattern in context
- Reference implementation: [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214)
- Migration PRs: [#261](https://github.com/Open-J-Proxy/ojp/pull/261) – [#284](https://github.com/Open-J-Proxy/ojp/pull/284)

| Status        | APPROVED           |
|---------------|--------------------|
| Proposer(s)   | Rogerio Robetti    |
| Proposal date | 01/01/2026         |
| Approver(s)   | Rogerio Robetti    |
| Approval date | 01/01/2026         |

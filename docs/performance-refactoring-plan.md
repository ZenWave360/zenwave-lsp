# Performance Refactoring Plan

## Goal

- Make navigation, diagnostics, references, manifest binding, and event-flow rendering scale to large organization workspaces.
- Eliminate repeated parsing, repeated module resolution, O(n) reverse-reference scans, filesystem walks on hot paths, blocking sleep loops, and unnecessary tool-window redraws.

## Phase 0.5: Remove `awaitDiagnostics` spin-wait

### Problem

- `ZenwaveLspProjectService.diagnostics()` currently waits for `publishDiagnostics` by polling with `Thread.sleep()`.
- This can block IntelliJ annotation/inspection flows for up to ~200ms per request.
- That is unsafe for IDE responsiveness and violates the intended threading model.

### Plan

- For in-process server usage, `diagnostics()` should call the synchronous core diagnostics path directly.
- Keep `publishDiagnostics` support for transport symmetry and possible future out-of-process use.
- Do not make local diagnostics queries depend on async client callbacks.

### Required API change

To implement this phase, `ZenwaveLspServer` must expose a synchronous diagnostics accessor:

```kotlin
fun diagnostics(uri: String): List<org.eclipse.lsp4j.Diagnostic>
```

This delegates to `server.diagnostics(uri)` on `ZenwaveLanguageServer` and maps the result
through `DtoMapper`, matching the same path that `publishDiagnosticsIfHandled()` already uses.

`ZenwaveLspProjectService.diagnostics()` then becomes:

```kotlin
fun diagnostics(file, text, languageId): List<Diagnostic> {
    synchronize(file, text, languageId)
    return server.diagnostics(file.url)
}
```

`awaitDiagnostics()` and `RecordingLanguageClient` can be removed for the in-process case,
or kept only if an out-of-process transport mode is added later.

### Expected outcome

- No blocking sleep in IntelliJ diagnostic flows.
- Diagnostics return synchronously from the in-process server.

### Implementation order

- Implement before Phase 1.
- This is a prerequisite for the cache and background-compute work.

## Phase 1: Unified document cache

### Problem

- Modules repeatedly re-parse the same document version across diagnostics, hover, definition, hierarchy, and xref work.
- Module resolution is also repeated per request.

### Cache placement decision

Use Option A: a separate parsed cache inside `ZenwaveLanguageServer`.

```text
ZenwaveLanguageServer holds:
  sessionStore: DocumentSessionStore            // text snapshots only
  parsedCache: Map<String, DocumentCacheEntry>  // new, keyed by uri
```

- `DocumentSessionStore` keeps its current shape (`DocumentSnapshot = uri + text + version`).
- `DocumentCacheEntry` is invalidated when `sessionStore.change()` or `sessionStore.close()` fires.
- The two stores have distinct lifecycles and can be tested independently.

Reason:
- keeps the session store as a pure document lifecycle tracker
- keeps parsed state as a separate, replaceable concern
- keeps the parsed cache as a `ZenwaveLanguageServer` implementation detail

### Plan

- Add a single parsed cache in `ZenwaveLanguageServer` keyed by `(uri, version)`.

### `parsedArtifact` type

Use Option A for the initial implementation:

- `parsedArtifact` is stored as `Any?`.
- Each `LanguageModule` is responsible for casting to its own internal parsed type when reading from cache.
- Unsafe casts remain contained inside the module that produced the artifact.

This is acceptable as long as `parsedArtifact` is only accessed through the module that produced it.

### Cache entry shape

```kotlin
DocumentCacheEntry(
  uri: String,
  version: Int,
  module: LanguageModule?,             // resolved once for this version
  diagnostics: List<Diagnostic>,       // eager: computed on open/change, triggers publishDiagnostics
  parsedArtifact: Any?,                // set during eager diagnostics build, reused by all subsequent feature calls
  hierarchy: List<HierarchyNode>?,     // lazy: computed on first hierarchy request for this version
  xrefContributions: List<...>?,       // lazy: computed on first xref request for this version
)
```

### Lazy vs eager build

- On open/change:
  - Module is resolved and bound.
  - Document is parsed.
  - `parsedArtifact` is set as a side effect of this single parse.
  - Diagnostics are extracted from the parse result and `publishDiagnostics` is triggered.
- On first hover/definition/hierarchy/xref call for this version:
  - The cached `parsedArtifact` is reused. No second parse.
  - Derived artifacts (`hierarchy`, `xrefContributions`) are computed lazily and stored.

### Expected outcome

- One parse per document version in steady state.
- Shared reuse across hover, definition, hierarchy, and xref indexing.

## Phase 1.5: Cache workspace config resolution

### Problem

- `ensureServer(file)` resolves `.zenwave/config.yml` by walking ancestor directories.
- Without caching, this happens on every LSP-backed editor operation.
- The filesystem walk happens before config equality checks, so it is always paid.

### Plan

- Cache resolved `ZenwaveWorkspaceConfig` by the directory where `.zenwave/config.yml` was found.
- On each call:
  - first check the cache
  - only walk ancestors if cache miss
- Invalidate:
  - ideally via `VirtualFileManager` / file watcher
  - otherwise via last-modified check
  - TTL only as fallback, not primary invalidation

### Expected outcome

- No repeated filesystem walk per hover/definition/diagnostics cycle in steady state.

### Implementation order

- Implement alongside or immediately after Phase 0.5.

## Phase 2: Module binding cache

### Problem

- `resolveModule()` iterates all modules and calls `canHandle(uri, text)` repeatedly.
- Content-sniffing modules like manifest/YAML pay unnecessary cost on every request.

### Plan

- Bind the module once at open/change time as part of the `DocumentCacheEntry`.
- Reuse the bound module for all feature calls for that version.
- Rebind only when the document version changes.

### Expected outcome

- No repeated `canHandle()` scans during steady-state requests.
- Module binding becomes part of the same cached document state.

### Note

- Structurally, this is already part of the Phase 1 cache entry.
- Phase 2 is about behavior and lifecycle, not a separate cache structure.

## Phase 3: Cross-reference secondary index

### Problem

- `reverseReferences()` currently scans all contributions across all source files.
- This is O(n) over workspace contributions and will not scale for org-level workspaces.

### Plan

- Maintain two indexes:
  - `contributionsBySourceUri`
  - `contributionsByTargetKey`
- `targetKey` format:
  - `"$targetUri#$targetSemanticId"`

### `remove(sourceUri)` cleanup order

- Both indexes must be updated atomically in this order:

```text
1. Fetch contributions = contributionsBySourceUri[sourceUri]
2. For each contribution, remove its key from contributionsByTargetKey
3. Remove sourceUri from contributionsBySourceUri
```

- Step 2 must happen before step 3.
- After step 3, the source contribution list is gone and target cleanup becomes impossible.

### `index()` re-index case

When `index(contributions)` is called for a `sourceUri` that is already tracked,
apply the same cleanup as `remove(sourceUri)` before inserting the new contributions:

```text
1. Fetch old contributions = contributionsBySourceUri[sourceUri]
2. For each old contribution, remove its key from contributionsByTargetKey
3. Replace contributionsBySourceUri[sourceUri] with the new contributions
4. For each new contribution, insert its key into contributionsByTargetKey
```

This ensures stale target-key entries from a previous index cycle are never left
in `contributionsByTargetKey` after a document is re-parsed.

### Synchronization requirement

The `remove()` and `index()` sequences described above must execute under the
same write lock as defined in the Thread Safety section of this plan.

A `ConcurrentHashMap` alone does not provide atomicity across these multi-step sequences.
Use a `ReentrantReadWriteLock` (JVM) or equivalent:

- `reverseReferences()` and `forwardReferences()` hold the read lock
- `index()` and `remove()` hold the write lock

This prevents a `reverseReferences()` call from observing a partially cleaned state
during the window between target-key cleanup and source-entry replacement/removal.

### Expected outcome

- Reverse references become indexed lookup instead of workspace-wide scan.

## Phase 4: Derived artifact reuse

### Problem

- Even after `parsedArtifact` caching, derived structures may still be recomputed per feature.

### Plan

- Store derived artifacts inside the same `DocumentCacheEntry`:
  - hierarchy
  - xref contributions
  - later: symbol tables, hover fragments, completion contexts if useful
- Compute lazily on first use for that version.
- Reuse for all subsequent requests until version changes.

### Expected outcome

- No repeated hierarchy/xref rebuilding for the same version.

## Phase 5: Event-flow tool-window redraw suppression

### Problem

- The ZFL/event-flow tool window should not redraw on every keystroke.
- Recomputing diagrams eagerly during typing wastes CPU and hurts responsiveness.

### Plan

- Debounce recomputation:
  - 250ms to 500ms after last change
- Cancel pending redraw when a newer edit arrives.
- Use version-first filter before hash:

```text
if (currentVersion == lastRenderedVersion) skip
else parse + enrich + hash, compare to lastRenderedHash
```

- Only compute semantic hash when version changed.
- If semantic/view-model hash matches the last rendered state, skip redraw.
- Redraw immediately only for:
  - explicit refresh
  - save
  - deliberate stale-view recovery action if needed

### Expected outcome

- No repaint storm while typing.
- No recompute for already-rendered versions.
- No redraw when semantic output is unchanged.

### Later extension

- Distinguish edits affecting only comments or formatting.
- Skip diagram recomputation without waiting for hash comparison.
- Deferred until incremental ZFL diff exists.

## Phase 6: Background compute for tool window

### Problem

- Even with debounce, parse/enrich/render work can still block responsiveness if done on hot UI paths.

### Plan

- Move event-flow recomputation to background execution.
- Use latest-request-wins behavior.
- In IntelliJ, prefer:

```kotlin
ReadAction.nonBlocking(...)
  .coalesceBy(identity)
  .submit(executor)
```

- Apply UI changes on EDT only after accepting the latest result.
- Drop stale results automatically when newer edits supersede them.

### Expected outcome

- Typing remains responsive even for large ZFL files.
- Tool window only applies fresh results.

## Phase 7: Removed

### Reason

- The prior “Selective Update” phase had no independent actionable scope beyond Phase 5.
- Short-term no-op redraw avoidance is already covered by debounce + version/hash checks.
- Incremental semantic diff is explicitly out of scope for this refactor.

## Phase 8: Workspace-scale guardrails and metrics

### Problem

- Large architecture workspaces need observability and limits.
- Without metrics, performance regressions will be hard to detect and tune.

### Plan

- Add guardrails and instrumentation for:
  - parse time by module
  - cache hit/miss rate
  - module binding reuse
  - xref index size
  - reverse-reference lookup time
  - tool-window recompute frequency
  - dropped stale recompute count
- Add upper-bound protections:
  - avoid eager parsing of unopened files unless explicitly indexed
  - avoid flattening workspace-wide collections on hot paths
  - cap verbose debug logging on large sessions

### Metrics implementation timing

- Some metrics should be added during earlier phases:
  - parse time per module: Phase 1
  - cache hit/miss rate: Phase 1
  - xref index size and lookup time: Phase 3
  - tool-window recompute frequency and dropped stale updates: Phase 5/6
- Phase 8 remains the place for guardrails and tuning based on real usage.

### Expected outcome

- Performance becomes measurable.
- Upper bounds are enforced before workspace size turns them into outages.

## Thread Safety (cross-cutting)

### Problem

- IntelliJ may dispatch diagnostics, hover, definition, and references from concurrent threads.
- Existing `linkedMapOf`-based stores are not safe for concurrent mutation/read.
- Lazy cached fields require safe publication.

### Plan

- Replace non-thread-safe mutable maps in session/document/xref stores with `ConcurrentHashMap` or equivalent safe structures.
- Ensure lazy cached fields are safely published using one of:
  - atomic references
  - compare-and-set pattern
  - explicit mutex where atomic multi-field update is required
- For cross-reference indexes specifically, use a read/write lock around multi-step update sequences:
  - read lock for `reverseReferences()` / `forwardReferences()`
  - write lock for `index()` / `remove()`
- Do not use default `by lazy {}` on shared mutable KMP state unless the thread-safety behavior is explicit and valid for the target runtime.

### Applies to

- Phase 1: document cache entries
- Phase 2: module binding state
- Phase 3: cross-reference indexes
- Phase 4: derived artifact fields
- Phase 5/6: render-state coordination

## Implementation order

1. Phase 0.5: remove `awaitDiagnostics` polling
2. Thread-safety baseline for caches/indexes
3. Phase 1: unified document cache
4. Phase 1.5: workspace config resolution cache
5. Phase 2: module binding reuse via cache entry
6. Phase 3: reverse-reference secondary index
7. Phase 4: derived artifact reuse
8. Phase 5: event-flow debounce + version/hash no-op suppression
9. Phase 6: background/coalesced tool-window recompute
10. Phase 8: guardrails and metrics completion

## Success criteria

- Diagnostics do not block on `Thread.sleep` polling.
- One parse per document version in steady state.
- Module binding is not recomputed per request.
- Reverse references do not scan all workspace contributions.
- `.zenwave/config.yml` lookup does not walk the filesystem on every request.
- Event-flow tool window does not redraw on every keystroke.
- Background recompute drops stale work and preserves editor responsiveness.
- Cache/index state remains correct under concurrent IntelliJ access.

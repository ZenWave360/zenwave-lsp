# ZenWave LSP — Agentic Coding Plan

## Goals

Build a single multi-language LSP server supporting four language modules in one shared platform:

- **ZDL** — domain modeling (`.zdl`)
- **OpenAPI / AsyncAPI** — API specs (`.yml`, `.yaml`, `.json`)
- **Avro** — event schema (`.avsc`)
- **ZFL** — flow modeling (`.zfl`)

Priority order: ZDL → OpenAPI/AsyncAPI → ZFL. Avro follows AsyncAPI.

## Architecture Summary

```
lsp-core (Kotlin Multiplatform, shared platform)
├── contracts/       shared DTOs + service interfaces
├── platform/        facade, module registry, session store
├── xref/            cross-reference index
├── zdl/             ZDL language module
├── zfl/             ZFL language module
├── spec/            OpenAPI + AsyncAPI language modules
├── avro/            Avro language module
└── jsonpath/        KMP JSONPath evaluator (no external libs)

lsp-jvm (JVM transport)
└── LSP4J adapter

lsp-js (JS transport — later)
└── vscode-languageserver adapter
```

One JVM process. One IntelliJ plugin. Routing is internal by file extension and lightweight content detection.

## Key Design Rules

- All contracts in `commonMain` — no platform types in shared code
- `LanguageModule` is the single extension point — every language implements it
- `CrossReferenceIndex` is shared, built lazily from all module contributions
- Semantic identity is a ZenWave canonical semantic pointer: `uri#<path>`
- OpenAPI, AsyncAPI, and Avro are first-class languages, not just companion resources for ZDL/ZFL
- Activation is by extension plus lightweight content detection for ambiguous formats; workspace/project detection is deferred
- No external libraries for JSONPath — implement KMP-native evaluator
- `json-schema-ref-parser-kmp` for JSON/YAML parsing and `$ref` resolution
- Transport adapters (LSP4J) delegate all semantics to the shared facade — no language logic in transport

## Canonical Semantic Pointer

All semantic identities in the server use a canonical pointer string:

- Format: `uri#<path>`
- Examples:
  - `file:///workspace/orders.zdl#entities.CustomerOrder`
  - `file:///workspace/openapi.yml#$['paths']['/orders/{id}']['get']`
  - `file:///workspace/asyncapi.yml#$['channels']['OrdersChannel']['subscribe']['message']`
  - `file:///workspace/schema.avsc#$['fields']['status']`

### Path rules

- Root is always `$`
- Use dot notation only for simple identifier-like property names
- Use bracket notation for keys containing `/`, `{}`, `-`, spaces, `@`, dots that are part of the key, or any ambiguous character
- Arrays may use numeric indices when no stable semantic key exists
- Whatever can be referenced by name should be referenced by name instead of numeric index
- Wildcards, filters, recursive descent, unions, and script expressions are out of scope

### Normalization rules

- The server may accept either dot or bracket notation as input
- The server must serialize semantic IDs in one canonical form
- The preferred canonical form is dot notation for simple names and bracket notation for ambiguous keys
- Equality is based on normalized pointer segments, not raw string formatting

---

## Phase 1 — Shared Core Contracts

### Goal
Define the stable shared interfaces and DTOs that all language modules and the transport layer depend on. This is the foundation — nothing else can proceed without it.

### Input state
- Existing `lsp-core` with partial ZDL/ZFL implementation
- Existing types: `Position`, `Range`, `Problem`, `SemanticModel`, `Location`, `ZenwaveLanguageService`

### Tasks

**1.1 — Define shared DTOs in `contracts` package**

Create `lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/contracts/`:

```kotlin
// Document identity
data class DocumentRef(val uri: String, val languageId: String, val version: Int)
data class DocumentSnapshot(val ref: DocumentRef, val text: String)

// Position + location (LSP-style, zero-based)
data class Position(val line: Int, val character: Int)
data class Range(val start: Position, val end: Position)
data class SourceLocation(val uri: String, val range: Range)

// Semantic identity
typealias SemanticId = String  // canonical pointer, e.g. "file:///workspace/orders.zdl#entities.Customer.fields.id"

// Hierarchy
data class HierarchyNode(
    val id: SemanticId,
    val label: String,
    val kind: String,           // "entity", "service", "flow", "path", "channel", "record", etc.
    val language: String,       // "zdl", "zfl", "openapi", "asyncapi", "avro"
    val source: SourceLocation,
    val children: List<HierarchyNode>,
    val relatedResources: List<NavigationTarget> = emptyList(),
    val uiHints: Map<String, String> = emptyMap()
)

// Navigation
data class NavigationTarget(
    val targetKind: String,     // "definition", "related-resource", "generated-artifact"
    val label: String,
    val uri: String,
    val range: Range?,
    val category: String?,      // "zdl", "zfl", "openapi", "asyncapi", "avro", "source-code"
    val relationType: String?,  // "declares", "uses", "emits", "consumes", "references", "declares-domain"
    val iconHint: String? = null
)

// Diagnostics
enum class DiagnosticSeverity { ERROR, WARNING, INFO, HINT }
data class Diagnostic(
    val uri: String,
    val range: Range,
    val severity: DiagnosticSeverity,
    val message: String,
    val code: String?,          // semantic path / error code
    val source: String = "zenwave-lsp",
    val data: Map<String, String> = emptyMap()
)

// Hover
data class HoverResult(
    val semanticId: SemanticId,
    val markdown: String,
    val range: Range?
)

// Completion
data class CompletionItem(
    val label: String,
    val kind: String,
    val detail: String?,
    val insertText: String
)

// Server capabilities
data class LanguageCapabilities(
    val languageId: String,
    val extensions: List<String>,
    val supportsHover: Boolean,
    val supportsDefinition: Boolean,
    val supportsCompletion: Boolean,
    val supportsDiagnostics: Boolean,
    val supportsHierarchy: Boolean,
    val supportsReferences: Boolean
)
```

**1.2 — Define `LanguageModule` interface**

Create `lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/contracts/LanguageModule.kt`:

```kotlin
interface LanguageModule {
    val languageId: String
    val capabilities: LanguageCapabilities

    /** Parse document and return a semantic model (opaque to the platform) */
    fun parse(snapshot: DocumentSnapshot): ParseResult

    /** Return normalized diagnostics from the parsed model */
    fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic>

    /** Return hover info at position */
    fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult?

    /** Return definition navigation targets at position */
    fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget>

    /** Return the full conceptual hierarchy for the document */
    fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode>

    /** Return cross-reference contributions from this document (for the index) */
    fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution>

    /** Detect whether a given document URI/text belongs to this module */
    fun canHandle(uri: String, text: String?): Boolean
}

data class ParseResult(
    val semanticId: String,
    val model: Any,             // language-specific, opaque to platform
    val diagnostics: List<Diagnostic>
)
```

**1.3 — Define `CrossReferenceIndex` interfaces**

Create `lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/xref/CrossReferenceIndex.kt`:

```kotlin
data class CrossReferenceContribution(
    val sourceUri: String,
    val sourceSemanticId: SemanticId,
    val sourceRange: Range?,
    val sourceLabel: String,
    val targetUri: String,
    val targetSemanticId: SemanticId?,
    val targetRange: Range?,
    val targetLabel: String?,
    val relationType: String    // "references", "declares-domain", "emits", "consumes", "schema-of"
)

interface CrossReferenceIndex {
    /** Index contributions from one document. Replaces previous contributions for that URI. */
    fun index(contributions: List<CrossReferenceContribution>)

    /** Remove all contributions from a URI (document closed/deleted) */
    fun remove(uri: String)

    /** Forward: what does this element reference/use? */
    fun forwardReferences(sourceUri: String, sourceSemanticId: SemanticId): List<NavigationTarget>

    /** Reverse: who references this element? */
    fun reverseReferences(targetUri: String, targetSemanticId: SemanticId): List<NavigationTarget>
}
```

**1.4 — Define `DocumentSessionStore` interface**

Create `lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/platform/DocumentSessionStore.kt`:

```kotlin
interface DocumentSessionStore {
    fun open(snapshot: DocumentSnapshot)
    fun change(uri: String, text: String, version: Int)
    fun close(uri: String)
    fun get(uri: String): DocumentSnapshot?
    fun getAll(): List<DocumentSnapshot>
}
```

**1.5 — Define `ZenwaveLanguageServer` facade**

Create `lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/platform/ZenwaveLanguageServer.kt`:

```kotlin
class ZenwaveLanguageServer(
    private val modules: List<LanguageModule>,
    private val sessionStore: DocumentSessionStore,
    private val crossReferenceIndex: CrossReferenceIndex
) {
    fun capabilities(): List<LanguageCapabilities>
    fun open(snapshot: DocumentSnapshot)
    fun change(uri: String, text: String, version: Int)
    fun close(uri: String)
    fun diagnostics(uri: String): List<Diagnostic>
    fun hover(uri: String, position: Position): HoverResult?
    fun definition(uri: String, position: Position): List<NavigationTarget>
    fun hierarchy(uri: String): List<HierarchyNode>
    fun forwardReferences(uri: String, semanticId: SemanticId): List<NavigationTarget>
    fun reverseReferences(uri: String, semanticId: SemanticId): List<NavigationTarget>
}
```

**1.6 — Migrate existing code to new contracts**

- Move `Position` and `Range` to `contracts` package (or alias from there)
- Mark existing `ZenwaveLanguageService` as deprecated / to be replaced
- Keep `ZdlParserAdapter` and `ZflParserAdapter` as-is (they will be wrapped by language modules in Phase 2/5)

### Acceptance criteria
- All interfaces compile in `commonMain` with no platform imports
- No circular dependencies between packages
- Existing tests still pass

---

## Phase 2 — ZDL Language Module

### Goal
Implement `ZdlLanguageModule` behind the `LanguageModule` interface with hierarchy, diagnostics, hover, same-file definition, and cross-reference contributions.

### Input state
- Phase 1 contracts complete
- Existing `ZdlParserAdapter`, `ZdlSemanticModel`

### Tasks

**2.1 — Implement `ZdlLanguageModule`**

Package: `io.zenwave360.lsp.core.zdl`

- Wrap existing `ZdlParserAdapter`
- `canHandle`: returns true for `.zdl` extension
- `parse`: call adapter, return `ParseResult` with model = `ZdlModel`
- `diagnostics`: normalize `ZdlModel.problems` to `List<Diagnostic>`, location from `locations` side table (1-based → 0-based line conversion)
- `hover`: cursor position → JSONPath via `ZdlSemanticModel.getLocation`, return `HoverResult` with markdown summary of the element
- `definition`: for field types, service aggregates, event names — resolve within same file using model maps

**2.2 — Implement `ZdlHierarchyBuilder`**

Build `List<HierarchyNode>` from `ZdlModel`:

```
document
├── apis
│   ├── asyncapi(provider) OrdersAsyncAPI   kind="api"
│   └── openapi(client) PaymentsOpenAPI     kind="api"
├── aggregates
│   └── CustomerOrderAggregate              kind="aggregate"
│       └── customerOrderCommand            kind="command"
├── entities
│   └── CustomerOrder                       kind="entity"
│       └── status: OrderStatus             kind="field"
├── enums
│   └── OrderStatus                         kind="enum"
├── services
│   └── OrdersService                       kind="service"
│       └── cancelOrder                     kind="method"
├── events
│   └── OrderEvent                          kind="event"
└── relationships                           kind="section"
```

`id` = `"${uri}#${jsonPath}"` e.g. `"file:///workspace/orders.zdl#entities.CustomerOrder"`
`source` = resolved from `ZdlModel.locations`

**2.3 — Implement `ZdlCrossReferenceContributor`**

Emit `CrossReferenceContribution` for:

| Source | Target | relationType |
|--------|--------|-------------|
| `apis.OrdersAsyncAPI` | asyncapi.yml URI from `uri` field | `"references"` |
| `apis.PaymentsOpenAPI` | openapi.yml URI from `uri` field | `"references"` |
| `events.OrderEvent` (with `@asyncapi` option) | asyncapi.yml channel | `"emits"` |
| `services.OrdersService.methods.cancelOrder` (with `@asyncapi`) | asyncapi.yml channel | `"publishes"` |

URI resolution: relative to the ZDL document URI.

**2.4 — Tests**

- `ZdlLanguageModuleTest`: hierarchy shape, diagnostic normalization, hover returns correct semanticId, definition resolves same-file type reference
- Use existing ZDL sample files from `lsp-core/src/commonTest`

### Acceptance criteria
- Hierarchy test asserts `entities.CustomerOrder` node has correct `kind`, `id`, and `source.range`
- Diagnostics test asserts unknown type error has non-zero range (not fallback zero)
- CrossReference test asserts api uri references are emitted with correct `relationType`

---

## Phase 3 — KMP Semantic Pointer Evaluator + YAML Document Model

### Goal
Implement a KMP-native semantic pointer evaluator and a YAML/JSON document model that enables position-to-path cursor mapping. This is the foundation for all OpenAPI/AsyncAPI/Avro features.

### Input state
- Phase 1 contracts complete
- `json-schema-ref-parser-kmp` available as dependency

### Tasks

**3.1 — Add `json-schema-ref-parser-kmp` dependency**

Add to `lsp-core/build.gradle.kts`. Verify it compiles for both JVM and JS targets.

**3.2 — Implement `SemanticPointerEvaluator`**

Package: `io.zenwave360.lsp.core.jsonpath`

Implement a minimal semantic pointer evaluator sufficient for LSP use cases:

```kotlin
object SemanticPointerEvaluator {
    /** Navigate a parsed JSON/YAML map with a canonical semantic path expression */
    fun evaluate(model: Map<String, Any?>, path: String): Any?

    /** Given a canonical path, return the SourceLocation from the document's location table */
    fun locationOf(locationTable: Map<String, IntArray>, path: String): SourceLocation?

    /** Given a cursor position, return the most specific canonical path that covers it */
    fun pathAtPosition(locationTable: Map<String, IntArray>, position: Position): String?
}
```

Supported subset:
- `$.components.schemas.OrderEvent` — dot-notation property access for simple names
- `$['paths']['/orders']['get']` — bracket notation
- `$['oneOf'][0]` — numeric index access when no stable semantic key exists
- No wildcards, filters, or recursive descent required for Phase 3

**3.3 — Implement `YamlDocumentModel`**

Package: `io.zenwave360.lsp.core.spec`

```kotlin
class YamlDocumentModel(
    val uri: String,
    val rawModel: Map<String, Any?>,       // from json-schema-ref-parser-kmp
    val locationTable: Map<String, IntArray>, // path → [startOffset, endOffset, startLine, startCol, endLine, endCol]
    val resolvedRefs: Map<String, String>  // $ref string → resolved URI#path
) {
    fun pathAtPosition(position: Position): String?
    fun locationOf(path: String): SourceLocation?
    fun resolveRef(ref: String, baseUri: String): String?
    fun nodeAt(path: String): Any?
}
```

**3.4 — Tests**

- `SemanticPointerEvaluatorTest`: path navigation, normalization, position lookup, bracket/dot notation
- `YamlDocumentModelTest`: parse a minimal OpenAPI YAML, assert `pathAtPosition` returns correct path at known line/character

### Acceptance criteria
- Position → canonical path round-trip works for both JSON and YAML inputs
- `$ref` resolution returns the target URI + fragment path
- All tests pass in both JVM and JS targets

---

## Phase 4 — OpenAPI and AsyncAPI Language Modules

### Goal
Implement `OpenApiLanguageModule` and `AsyncApiLanguageModule` as full language modules with hierarchy, diagnostics, hover, definition (including cross-file `$ref`), and cross-reference contributions.

### Input state
- Phase 1 contracts complete
- Phase 3 `YamlDocumentModel` and `JsonPathEvaluator` complete

### Tasks

**4.1 — Implement `OpenApiLanguageModule`**

Package: `io.zenwave360.lsp.core.spec.openapi`

- `canHandle`: `.yml`/`.yaml`/`.json` files that contain `openapi:` top-level key
- `parse`: use `json-schema-ref-parser-kmp` to parse and resolve `$ref`s, build `YamlDocumentModel`
- `diagnostics`: check required fields (`openapi`, `info`, `paths`); flag unresolvable `$ref`s
- `hover`: cursor → JSONPath → node → markdown summary (operation summary/description, schema title/description)
- `definition`: if cursor is on a `$ref` value, resolve to target URI + range

**4.2 — Implement `OpenApiHierarchyBuilder`**

```
document (openapi.yml)
├── info                         kind="info"
├── paths                        kind="section"
│   └── /orders                  kind="path"
│       ├── get: listOrders      kind="operation"
│       ├── post: createOrder    kind="operation"
│       └── /orders/{id}         kind="path"
│           └── get: getOrder    kind="operation"
├── components                   kind="section"
│   ├── schemas                  kind="section"
│   │   └── CustomerOrder        kind="schema"
│   │       └── status           kind="field"
│   └── requestBodies            kind="section"
└── (role badge: provider/client from ZDL reference — added via uiHints)
```

**4.3 — Implement `AsyncApiLanguageModule`**

Package: `io.zenwave360.lsp.core.spec.asyncapi`

- `canHandle`: `.yml`/`.yaml`/`.json` files containing `asyncapi:` top-level key
- `parse`, `diagnostics`, `hover`, `definition`: same pattern as OpenAPI module
- `diagnostics`: check required fields (`asyncapi`, `info`, `channels`); flag unresolvable `$ref`s and unresolvable message schema references

**4.4 — Implement `AsyncApiHierarchyBuilder`**

```
document (asyncapi.yml)
├── info                         kind="info"
├── channels                     kind="section"
│   └── OrdersChannel            kind="channel"
│       ├── subscribe            kind="operation"
│       │   └── OrderEvent       kind="message"  (resolved $ref)
│       └── publish              kind="operation"
│           └── CreateOrderCmd   kind="message"
└── components                   kind="section"
    ├── schemas                  kind="section"
    │   └── OrderEvent           kind="schema"
    └── messages                 kind="section"
```

**4.5 — Implement `SpecCrossReferenceContributor`**

For AsyncAPI: emit contributions for each channel message schema:

| Source | Target | relationType |
|--------|--------|-------------|
| `channels.OrdersChannel.subscribe.message` | resolved schema URI (avsc or inline) | `"schema-of"` |
| `channels.OrdersChannel` | ZDL service methods (reverse — via index) | resolved at query time |

For OpenAPI: emit contributions for each `$ref` that crosses file boundaries.

**4.6 — Tests**

- Parse a minimal OpenAPI YAML, assert hierarchy has expected paths/operations
- Parse a minimal AsyncAPI YAML, assert channels/messages hierarchy
- Hover at a `$ref` line returns the target schema description
- Definition at `$ref` value returns `NavigationTarget` with correct URI + range
- Cross-reference contributions are emitted for message schemas

### Acceptance criteria
- `canHandle` returns true for valid OpenAPI files based on extension + lightweight content detection only
- OpenAPI hierarchy test asserts operation nodes have correct `kind` and `source.range`
- AsyncAPI message node links to its resolved schema URI

---

## Phase 5 — Avro Language Module

### Goal
Implement `AvroLanguageModule` for `.avsc` files. Avro schemas are JSON files; they appear as message schemas in AsyncAPI channels.

### Input state
- Phase 1 contracts complete
- Phase 3 `JsonPathEvaluator` complete (Avro files are JSON, not YAML)

### Tasks

**5.1 — Implement `AvroLanguageModule`**

Package: `io.zenwave360.lsp.core.avro`

- `canHandle`: `.avsc` extension
- `parse`: parse as JSON using `json-schema-ref-parser-kmp`, build location table
- `diagnostics`: validate required Avro fields (`type`, `name` for records; `type` for fields); flag unknown primitive types; flag duplicate field names
- `hover`: cursor → JSONPath → node → markdown (record doc, field type, namespace)
- `definition`: field `type` references to other named types within same file or referenced `.avsc`

**5.2 — Implement `AvroHierarchyBuilder`**

```
document (schema.avsc)
└── OrderEvent                    kind="record"    (namespace: io.zenwave360.orders)
    ├── id: string                kind="field"
    ├── status: OrderStatus       kind="field"
    └── OrderStatus               kind="enum"      (nested or referenced)
```

**5.3 — Implement `AvroCrossReferenceContributor`**

Emit:

| Source | Target | relationType |
|--------|--------|-------------|
| `OrderEvent` record | AsyncAPI messages that reference this avsc URI | resolved at query time via reverse index |

**5.4 — Tests**

- Parse a minimal `.avsc` file, assert hierarchy has record and fields
- Hover on a field type returns the type name in markdown
- Diagnostic emitted for unknown primitive type

### Acceptance criteria
- Hierarchy test asserts record node with correct `namespace` in `uiHints`
- Field type that is another record name resolves via definition to correct range

---

## Phase 6 — ZFL Language Module

### Goal
Implement `ZflLanguageModule` behind the `LanguageModule` interface with hierarchy, diagnostics, hover, same-file definition, and cross-reference contributions to ZDL and AsyncAPI.

### Input state
- Phase 1 contracts complete
- Existing `ZflParserAdapter`, `ZflSemanticModel`

### Tasks

**6.1 — Implement `ZflLanguageModule`**

Package: `io.zenwave360.lsp.core.zfl`

- `canHandle`: `.zfl` extension
- `parse`: wrap existing `ZflParserAdapter`
- `diagnostics`: normalize `ZflSemanticModel.diagnostics` (currently sparse — emit what exists, fall back gracefully)
- `hover`: cursor → JSONPath from `ZflSemanticModel.getLocation`, return element summary
- `definition`: same-file resolution for system/service/command/event name references

**6.2 — Implement `ZflHierarchyBuilder`**

Build from `ZflSemanticModel`:

```
document (subscriptions.zfl)
├── systems                       kind="section"
│   └── Subscription              kind="system"
│       └── SubscriptionService   kind="service"
└── flows                         kind="section"
    └── PaymentsFlow              kind="flow"
        ├── CustomerRequests...   kind="start"
        ├── when ... do ...       kind="policy"
        ├── renewSubscription     kind="command"
        ├── SubscriptionRenewed   kind="event"
        └── end                   kind="end"
```

`id` = `"${uri}#flows.PaymentsFlow.commands.renewSubscription"`

**6.3 — Implement `ZflCrossReferenceContributor`**

| Source | Target | relationType |
|--------|--------|-------------|
| `systems.Subscription` (with `@zdl("path")`) | ZDL file URI | `"declares-domain"` |
| `flows.PaymentsFlow.commands.renewSubscription` | ZDL service method (name match, scoped by declared ZDL) | `"uses"` |
| `flows.PaymentsFlow.events.SubscriptionRenewed` | ZDL event (name match, scoped by declared ZDL) | `"references"` |

**6.4 — Tests**

- Hierarchy test for a ZFL sample file: assert flow→policy→command→event nesting
- CrossReference test: `@zdl("subscription/model.zdl")` emits a `"declares-domain"` contribution
- Definition resolves a `command` name reference to its position within the same file

### Acceptance criteria
- Flow hierarchy matches ZFL sample in test resources
- `declares-domain` contribution target URI is correctly resolved relative to the ZFL document URI

---

## Phase 7 — Cross-Reference Index Implementation

### Goal
Implement the `CrossReferenceIndex` and wire it into `ZenwaveLanguageServer` so that forward and reverse navigation works across all four language modules.

### Input state
- Phases 1–6 complete (all modules emit contributions)
- `CrossReferenceIndex` interface from Phase 1

### Tasks

**7.1 — Implement `InMemoryCrossReferenceIndex`**

Package: `io.zenwave360.lsp.core.xref`

- Store: `Map<String, List<CrossReferenceContribution>>` keyed by source URI
- `index(contributions)`: replace all entries for the source URI
- `remove(uri)`: remove all entries where `sourceUri == uri`
- `forwardReferences(sourceUri, semanticId)`: filter stored contributions
- `reverseReferences(targetUri, semanticId)`: scan all contributions for matching target; return as `NavigationTarget` list pointing back at sources

**7.2 — Wire into `ZenwaveLanguageServer`**

On `open` and `change`:
1. Parse document via matching module
2. Call `module.crossReferenceContributions(snapshot)`
3. Call `index.index(contributions)`

On `close`:
- Call `index.remove(uri)`

On `forwardReferences`/`reverseReferences`:
- Delegate to index, enrich `NavigationTarget` with `label` and `iconHint` from module capabilities

**7.3 — Key navigation paths to test end-to-end**

| Query | Expected result |
|-------|----------------|
| Forward refs from `orders.zdl#apis.OrdersAsyncAPI` | `NavigationTarget` → `asyncapi.yml` |
| Forward refs from `asyncapi.yml#channels.OrdersChannel.subscribe.message` | `NavigationTarget` → `schema.avsc` |
| Reverse refs on `asyncapi.yml#channels.OrdersChannel` | ZDL service methods that reference this channel |
| Reverse refs on `schema.avsc#OrderEvent` | AsyncAPI messages that use this schema |
| Forward refs from `subscriptions.zfl#systems.Subscription` | `NavigationTarget` → `subscription/model.zdl` |

**7.4 — Tests**

- Unit test `InMemoryCrossReferenceIndex`: index, remove, forward, reverse
- Integration test: open ZDL + AsyncAPI + Avro snapshots, assert forward and reverse navigation results

### Acceptance criteria
- Reverse lookup from AsyncAPI channel returns at least one ZDL service method when properly indexed
- `remove(uri)` correctly clears stale references when a document is closed

---

## Phase 8 — Document Session Store

### Goal
Implement `DocumentSessionStore` with parse caching by version. Replaces the current stateless text-only service pattern.

### Input state
- Phase 1 contracts complete
- `ZenwaveLanguageServer` facade exists

### Tasks

**8.1 — Implement `InMemoryDocumentSessionStore`**

Package: `io.zenwave360.lsp.core.platform`

- Store: `Map<String, DocumentSnapshot>` keyed by URI
- Track version — ignore `change` calls with older version than current
- `open`: store snapshot, parse via matching module, cache `ParseResult`
- `change`: update snapshot, invalidate cached parse result
- `close`: remove from store and invalidate cache

**8.2 — Language detection**

`canHandle` cascade: iterate registered modules, first match wins. Tie-break rule: more specific extension wins over generic (`.zdl` > `.yml`).

Activation for Phase 1 implementation:

- `.zdl` → `ZdlLanguageModule`
- `.zfl` → `ZflLanguageModule`
- `.avsc` → `AvroLanguageModule`
- `.yml` / `.yaml` / `.json` → lightweight content detection (`asyncapi:` first, then `openapi:`)

Workspace or project-root identification is explicitly deferred.

**8.3 — Tests**

- Open + change + close lifecycle
- Version ordering: older change ignored
- Language detection: `.yml` with `openapi:` key → `OpenApiLanguageModule`
- Language detection: `.yml` with `asyncapi:` key → `AsyncApiLanguageModule`
- Language detection: generic `.json` without recognized format → no module matches

---

## Phase 9 — Diagnostics Polish

### Goal
Make diagnostics consistent, editor-grade, and correct across all four modules.

### Input state
- All language modules implemented

### Tasks

**9.1 — Normalize severities**

- ZDL: all current problems → `ERROR`. Add `WARNING` for deprecation-style hints if parser provides them.
- ZFL: `ZflSemanticDiagnostic.severity` maps directly.
- OpenAPI/AsyncAPI: missing required fields → `ERROR`; unknown extensions → `WARNING`; unresolvable `$ref` → `ERROR`.
- Avro: unknown type → `ERROR`; missing `doc` field → `INFO`.

**9.2 — Ensure URI is always present**

Every `Diagnostic` must have a corresponding URI so transport can route `publishDiagnostics` correctly.

**9.3 — Preserve semantic identity**

Set `Diagnostic.code` to the canonical path of the offending element. Set `Diagnostic.data["language"]` to the language id.

**9.4 — Tests**

- Each module has at least one diagnostic test asserting non-zero range, correct severity, and non-null code.

---

## Phase 10 — JVM LSP Transport (LSP4J)

### Goal
Wrap `ZenwaveLanguageServer` in a runnable JVM LSP server using LSP4J. This is the transport the IntelliJ plugin will connect to.

### Input state
- All phases above complete
- `lsp-jvm` module with LSP4J dependency declared

### Tasks

**10.1 — Implement `ZenwaveLspServer : LanguageServer, TextDocumentService`**

Package: `io.zenwave360.lsp.jvm`

Map LSP4J lifecycle:

| LSP4J method | ZenwaveLanguageServer call |
|---|---|
| `initialize` | return `ServerCapabilities` built from `server.capabilities()` |
| `didOpen` | `server.open(snapshot)` then `publishDiagnostics` |
| `didChange` | `server.change(...)` then `publishDiagnostics` |
| `didClose` | `server.close(uri)` |
| `hover` | `server.hover(uri, position)` → `Hover` with markdown content |
| `definition` | `server.definition(uri, position)` → `List<Location>` |

**10.2 — Map `ServerCapabilities`**

Build from `server.capabilities()`:
- `hoverProvider = true` if any module supports hover
- `definitionProvider = true` if any module supports definition
- `textDocumentSync = INCREMENTAL`
- Register document selectors per language id and file extension

**10.3 — Custom request for hierarchy**

Register `zenwave/hierarchy` as a custom LSP request:
- Request: `{ uri: string }`
- Response: `List<HierarchyNode>` serialized as JSON

**10.4 — Custom request for cross-references**

Register `zenwave/forwardReferences` and `zenwave/reverseReferences`:
- Request: `{ uri: string, semanticId: string }`
- Response: `List<NavigationTarget>`

**10.5 — `.yml`/`.json` activation filter**

The server only sends `textDocument/publishDiagnostics` for YAML/JSON files when `server.canHandle(uri)` returns true by extension + lightweight content detection.

**10.6 — `main` entrypoint**

```kotlin
fun main() {
    val server = ZenwaveLanguageServer(
        modules = listOf(ZdlLanguageModule(), AsyncApiLanguageModule(), OpenApiLanguageModule(), AvroLanguageModule(), ZflLanguageModule()),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex()
    )
    val launcher = LSPLauncher.createServerLauncher(ZenwaveLspServer(server), System.`in`, System.out)
    launcher.startListening()
}
```

**10.7 — Tests**

- Integration test: start server, send `initialize`, `didOpen` a ZDL file, assert `publishDiagnostics` is called with correct URI
- Integration test: `hover` request returns markdown with entity name

### Acceptance criteria
- Server starts and responds to `initialize` without error
- `didOpen` a known-good ZDL file produces zero diagnostics
- `didOpen` a ZDL file with a known error produces at least one diagnostic with non-zero range
- `hover` at a known entity declaration returns the entity name in markdown

---

## Phase 11 — Update Architecture Docs

### Goal
Update all docs to reflect the final implemented architecture.

### Tasks

- Update `lsp-architecture-and-contracts.md`: add OpenAPI/AsyncAPI/Avro modules, `LanguageModule` interface, `CrossReferenceIndex`
- Update `dsl-lsp-agentic-coding-plan.md`: mark phases complete, add new phases
- Archive or supersede `parser-architecture-and-contracts.md` with notes on what changed

---

## Recommended Agent Task Sequence

Each item below is a discrete agent task. Do not parallelize items in the same group.

### Group A (sequential foundation)
1. Phase 1: Shared core contracts
2. Phase 2: ZDL language module
3. Phase 3: Semantic pointer evaluator + YAML document model

### Group B (parallel after Group A)
- Phase 4: OpenAPI/AsyncAPI modules (depends on Phase 3)
- Phase 5: Avro module (depends on Phase 3)
- Phase 6: ZFL language module (depends on Phase 1 only)

### Group C (sequential after Group B)
7. Phase 7: Cross-reference index
8. Phase 8: Document session store
9. Phase 9: Diagnostics polish

### Group D (sequential after Group C)
10. Phase 10: JVM LSP transport
11. Phase 11: Update docs

---

## Module/Package Map

```
lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/
├── contracts/
│   ├── DocumentRef.kt
│   ├── HierarchyNode.kt
│   ├── NavigationTarget.kt
│   ├── Diagnostic.kt
│   ├── HoverResult.kt
│   ├── CompletionItem.kt
│   ├── LanguageModule.kt
│   └── LanguageCapabilities.kt
├── platform/
│   ├── ZenwaveLanguageServer.kt
│   ├── DocumentSessionStore.kt
│   └── InMemoryDocumentSessionStore.kt
├── xref/
│   ├── CrossReferenceIndex.kt
│   ├── CrossReferenceContribution.kt
│   └── InMemoryCrossReferenceIndex.kt
├── jsonpath/
│   └── SemanticPointerEvaluator.kt
├── spec/
│   ├── YamlDocumentModel.kt
│   ├── openapi/
│   │   ├── OpenApiLanguageModule.kt
│   │   └── OpenApiHierarchyBuilder.kt
│   └── asyncapi/
│       ├── AsyncApiLanguageModule.kt
│       └── AsyncApiHierarchyBuilder.kt
├── avro/
│   ├── AvroLanguageModule.kt
│   └── AvroHierarchyBuilder.kt
├── zdl/
│   ├── ZdlLanguageModule.kt
│   ├── ZdlHierarchyBuilder.kt
│   └── ZdlCrossReferenceContributor.kt
└── zfl/
    ├── ZflLanguageModule.kt
    ├── ZflHierarchyBuilder.kt
    └── ZflCrossReferenceContributor.kt

lsp-jvm/src/main/kotlin/io/zenwave360/lsp/jvm/
├── ZenwaveLspServer.kt
├── DtoMapper.kt
└── Main.kt
```

---

## Open Decisions (resolve before or during implementation)

1. **Canonical path serialization**: canonicalize to dot notation for simple names and bracket notation for ambiguous keys; accept both as input.
2. **AsyncAPI vs OpenAPI detection**: both use `.yml`. Detection order is `asyncapi:` first, then `openapi:`.
3. **`json-schema-ref-parser-kmp` location table**: confirm the library provides per-node position data, or plan to implement position tracking during parsing.
4. **ZFL diagnostic gaps**: ZFL semantic diagnostics are currently sparse. Accept this for Phase 6 and improve incrementally.
5. **Cross-file definition for ZDL**: same-file definition is Phase 2. Cross-file (e.g., entity type defined in another `.zdl`) is deferred — mark as `planned` in capabilities.
6. **Workspace identification**: explicitly deferred. Current activation is by extension plus lightweight content detection only.

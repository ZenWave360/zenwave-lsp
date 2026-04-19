# ZenWave Multi-Language LSP — Architecture and Contracts

## 1. Purpose

The ZenWave language server project is the language-intelligence layer for ZenWave DSLs and related API/schema formats, with shared infrastructure supporting:

- ZFL
- ZDL
- OpenAPI
- AsyncAPI
- Avro

Its role is to:

- parse ZFL or ZDL text through the existing ZenWave parser stack
- normalize parser output into a language-service-facing semantic model
- answer editor-oriented queries from that semantic model
- provide a transport-neutral core that can later be exposed over standard LSP transports

In the current repository state, the implemented center of gravity is `lsp-core`: a Kotlin Multiplatform service API that accepts document text plus a language selector and returns semantic data, diagnostics, and a small set of query results.

This means the project is currently best understood as:

- implemented today: language-service core
- planned: transport adapters that expose the core as an actual LSP server/service

## 2. Scope of responsibility

### What the language server owns

The language server owns:

- document-to-semantic-model conversion by invoking the parser layer
- normalization of parser problems into language-service diagnostics
- cursor-position-to-semantic-location resolution via a canonical semantic pointer path
- editor/query features built on top of semantic model lookups
- a language-neutral service contract across ZFL, ZDL, OpenAPI, AsyncAPI, and Avro

### What the language server does not own

The language server does not own:

- grammar definition or low-level parsing algorithms
- IntelliJ PSI trees, stubs, indexing, or editor rendering
- event flow diagram rendering or layout decisions
- code generation, project scaffolding, or artifact generation
- authoritative ownership of OpenAPI or AsyncAPI files

### Boundary with parser

The parser is a dependency, not a responsibility of this repository's core contracts.

- Parser responsibility: parse source text, build domain model/map structure, provide location lookup, provide raw validation problems.
- Language server responsibility: wrap that parser output into a stable service-facing model and answer IDE queries from it.

### Boundary with IntelliJ plugin

The IntelliJ plugin should treat this project as a document/URI/range-driven language intelligence service.

- Plugin responsibility: editor wiring, transport/session lifecycle, UI presentation, tool windows, icon mapping, caching on the client if desired.
- Language server responsibility: semantic lookup, diagnostics, navigation payloads, hierarchy payloads, and related-resource payloads when implemented.

The plugin should not assume direct access to parser internals, Kotlin classes, or semantic-map implementation details beyond documented contracts.

### Boundary with event flow renderer

The event flow renderer is a consumer of conceptual data, not part of the language server.

- Renderer responsibility: visual graph/tree rendering, grouping, layout, edge styling, user interaction.
- Language server responsibility: provide conceptual nodes, source anchors, and related navigation targets that a renderer can consume.

The language server should expose semantic structure, not rendering instructions.

## 3. Technology and protocol model

### Language and stack

- Language: Kotlin
- Core module: Kotlin Multiplatform
- Targets configured today: JVM and JS IR
- JVM toolchain: Java 17
- Parser dependency: `io.zenwave360.dsl:dsl-kotlin:1.5.0-SNAPSHOT`

### Real LSP server or planned LSP-compatible service

Current state:

- `lsp-core` is implemented and usable as an in-process language service API.
- `lsp-jvm` and `lsp-js` declare dependencies on LSP transport libraries but do not currently contain transport/server source code in this repository snapshot.

Therefore:

- current implementation is not yet a complete runnable LSP server in this repo
- current implementation is a planned LSP-compatible core intended to sit behind a future JVM or JS transport layer

### Transport assumptions

Transport is not implemented here, but the repository structure strongly implies these intended adapters:

- JVM side: `lsp4j`
- JS side: `vscode-languageserver`

Expected transport style for future integration:

- standard LSP request/response and notification flow
- text document identified by URI
- position/range addressed in LSP-style zero-based lines/characters

### Runtime assumptions

Current runtime assumptions for the implemented core:

- parser invocation is synchronous
- requests are text-based, not file-system-index-based
- the service reparses on demand from provided text
- no internal workspace indexing or persistent document store exists in `lsp-core`
- no concurrency/caching contract is documented or implemented

## 4. Dependency on parser

The language server depends directly on the ZenWave parser library and currently embeds parser usage as an in-process library call.

Implementation model today:

- `ZenwaveLanguageServiceImpl` depends on two parser abstractions: `ZdlParser` and `ZflParser`
- `ZdlParserAdapter` wraps `io.zenwave360.language.zdl.ZdlParser`
- `ZflParserAdapter` wraps `io.zenwave360.language.zfl.ZflParser`
- each adapter calls the parser library directly and returns a `SemanticModel`

So the parser is:

- not called over RPC
- not shell-executed
- not delegated through a remote microservice
- embedded as a library dependency behind a thin adapter layer

The important architectural boundary is that `lsp-core` depends on parser interfaces, while the default adapters bind those interfaces to the existing `dsl-kotlin` implementation.

## 5. Externally relevant services

### Implemented today

The following service surfaces exist today in `lsp-core`:

- parse
- diagnostics
- hover
- completion
- definition

Current behavior:

- `parse(language, text)` returns a `SemanticModel`
- `diagnostics(language, text)` returns normalized parser problems
- `hover(language, text, position)` returns a `jsonPath` string for the semantic element under the cursor
- `completion(...)` is currently stubbed and returns an empty list
- `definition(...)` is currently stubbed and returns `null`

### Semantically important internal concept

The core semantic contract already present is:

- cursor position -> semantic `jsonPath`

Examples from verified ZDL tests:

- `entities.Customer.fields.customerId.name`
- `entities.Customer.fields.customerId.type`
- `entities.Customer.fields.customerId.validations.required`
- `entities.Customer.body`

That mapping is the current foundation for all higher-level IDE features.

### Externally relevant services for IDE integration

For IntelliJ integration, the relevant service categories are:

- conceptual hierarchy: not implemented as an API today, but directly derivable from semantic model data and `jsonPath` identifiers
- navigation targets: structurally planned; current `Location` type already exists but definition resolution is not implemented
- references: not implemented
- diagnostics: implemented
- symbol/document queries: not implemented as dedicated APIs today
- related external resources such as ZDL/OpenAPI/AsyncAPI/source artifacts: not implemented as dedicated APIs today, but source DSL examples show that such references exist in the language model and are a valid future direction

### Future support for ZDL / OpenAPI / AsyncAPI references

### First-class support for OpenAPI / AsyncAPI / Avro

OpenAPI, AsyncAPI, and Avro are first-class language modules in the target architecture, not merely companion resources attached to ZFL or ZDL.

That means the server should be able to provide value for:

- standalone OpenAPI documents
- standalone AsyncAPI documents
- standalone Avro schema files
- cross-language navigation when those files are referenced from ZDL or ZFL

Cross-language enrichment is additive. It does not define whether a file is eligible for language-service support.

The sample ZFL and ZDL documents already carry reference-like information such as:

- ZFL `@zdl("subscription/model.zdl")`
- ZDL `apis { asyncapi(...) { uri "..." } }`
- ZDL plugin config blocks containing `specFile`, `zdlFile`, and target file paths
- service annotations such as `@asyncapi({api: ..., channel: ...})`

This supports a future contract in which the language service emits related resources and navigation targets for:

- referenced ZDL files
- referenced OpenAPI specs
- referenced AsyncAPI specs
- generated or related source code artifacts

That capability is not implemented in the current repository snapshot.

### Shared-core parsing direction

The current direction is to parse OpenAPI, AsyncAPI, and Avro in the shared Kotlin Multiplatform core using `json-schema-ref-parser-kmp` plus ZenWave-owned pointer/navigation logic.

That means:

- `ApiSpecResolver` is not the target architecture and should be retired
- shared-core contracts should be designed around direct parsing and direct navigation
- platform-specific transports remain thin and should not own spec semantics

Platform differences may still affect performance or optional enrichment, but they should not create separate parsing contracts for spec languages.

### Activation model

The current activation direction is:

- `.zdl` activates ZDL
- `.zfl` activates ZFL
- `.avsc` activates Avro
- `.yml`, `.yaml`, and `.json` activate spec language detection by lightweight content inspection

Workspace/project identification is explicitly deferred. The server should not require a ZenWave project marker to activate OpenAPI, AsyncAPI, or Avro support.

### Canonical semantic pointer

The stable semantic identity format is:

- `uri#<path>`

Examples:

- `file:///workspace/orders.zdl#entities.CustomerOrder`
- `file:///workspace/openapi.yml#$['paths']['/orders/{id}']['get']`
- `file:///workspace/asyncapi.yml#$['channels']['OrdersChannel']['subscribe']['message']`
- `file:///workspace/schema.avsc#$['fields']['status']`

The `<path>` segment is a ZenWave-owned canonical semantic pointer:

- root is always `$`
- dot notation is used for simple identifier-like names
- bracket notation is used for ambiguous keys and special characters
- arrays may use numeric indices only when no stable named identity exists
- whenever something can be referenced by name, name-based identity is preferred over index-based identity
- filters, wildcards, recursive descent, and scripting are out of scope

## 6. Conceptual hierarchy contract

### Status

- Current implementation status: not implemented as a formal API
- Recommended contract status: proposed for IntelliJ integration

### Intent

The IntelliJ plugin needs a stable tree model for a tool window. The correct stable identifier in this codebase is the canonical semantic pointer `uri#<path>`.

### Proposed contract

```json
{
  "id": "file:///workspace/subscriptions.zfl#flows.PaymentsFlow",
  "label": "PaymentsFlow",
  "kind": "flow",
  "children": [
    {
      "id": "file:///workspace/subscriptions.zfl#flows.PaymentsFlow.systems.Subscription",
      "label": "Subscription",
      "kind": "system",
      "children": [
        {
          "id": "file:///workspace/subscriptions.zfl#flows.PaymentsFlow.systems.Subscription.service",
          "label": "service",
          "kind": "service",
          "children": [
            {
              "id": "file:///workspace/subscriptions.zfl#flows.PaymentsFlow.systems.Subscription.service.commands.renewSubscription",
              "label": "renewSubscription",
              "kind": "command",
              "children": [],
              "source": {
                "uri": "file:///workspace/subscriptions.zfl",
                "range": {
                  "start": { "line": 15, "character": 26 },
                  "end": { "line": 15, "character": 43 }
                }
              }
            }
          ],
          "source": {
            "uri": "file:///workspace/subscriptions.zfl",
            "range": {
              "start": { "line": 14, "character": 12 },
              "end": { "line": 16, "character": 13 }
            }
          }
        }
      ],
      "source": {
        "uri": "file:///workspace/subscriptions.zfl",
        "range": {
          "start": { "line": 13, "character": 8 },
          "end": { "line": 17, "character": 9 }
        }
      },
      "relatedNavigationTargets": [
        {
          "targetKind": "zdl",
          "label": "subscription/model.zdl",
          "uri": "file:///workspace/subscription/model.zdl",
          "range": null,
          "relationType": "declares-domain"
        }
      ]
    }
  ],
  "source": {
    "uri": "file:///workspace/subscriptions.zfl",
    "range": {
      "start": { "line": 9, "character": 0 },
      "end": { "line": 76, "character": 1 }
    }
  },
  "uiHints": {
    "expandedByDefault": true,
    "iconHint": "flow"
  }
}
```

### Field definitions

- `id`: stable semantic identifier. Recommended value: canonical semantic pointer `uri#<path>`.
- `label`: user-facing text.
- `kind`: semantic node kind such as `flow`, `system`, `service`, `command`, `event`, `start`, `end`, `aggregate`, `entity`.
- `children`: ordered child nodes for tree rendering.
- `source`: source anchor for navigation.
- `relatedNavigationTargets`: optional navigable related items.
- `uiHints`: optional non-semantic hints for presentation.

### Source metadata shape

```json
{
  "uri": "file:///workspace/subscriptions.zfl",
  "range": {
    "start": { "line": 9, "character": 0 },
    "end": { "line": 76, "character": 1 }
  }
}
```

### UI hint guidance

UI hints should stay optional and weakly binding. Suggested fields:

- `iconHint`
- `expandedByDefault`
- `groupHint`
- `sortKey`

The plugin should remain free to override them.

## 7. Navigation target contract

### Status

- Current implementation status: partially modeled only
- Existing type in code: `Location(uri, range)`
- Definition resolution status: not implemented
- Recommended contract status: proposed

### Proposed contract

```json
{
  "targetKind": "definition",
  "label": "CustomerOrder",
  "uri": "file:///workspace/models/orders.zdl",
  "range": {
    "start": { "line": 73, "character": 7 },
    "end": { "line": 73, "character": 20 }
  },
  "category": "entity",
  "iconHint": "entity",
  "relationType": "declares"
}
```

### Field definitions

- `targetKind`: kind of navigation result such as `definition`, `declaration`, `implementation`, `related-resource`, `generated-artifact`.
- `label`: display label.
- `uri`: destination document URI.
- `range`: destination range in zero-based line/character coordinates.
- `category`: optional semantic category such as `flow`, `entity`, `event`, `service`, `openapi`, `asyncapi`, `source-code`.
- `iconHint`: optional presentation hint.
- `relationType`: optional relation such as `declares`, `uses`, `emits`, `consumes`, `references`, `generated-from`.

### Compatibility with current code

The only currently implemented concrete location shape in this repository is:

```json
{
  "uri": "file:///workspace/models/orders.zdl",
  "range": {
    "start": { "line": 73, "character": 7 },
    "end": { "line": 73, "character": 20 }
  }
}
```

The richer fields above should be treated as a forward-compatible wrapper around that basic shape.

## 8. References / related resources contract

### Status

- Current implementation status: not implemented
- Architectural direction: strongly indicated by sample DSL constructs

### Intent

A ZFL or ZDL element may need to expose external or cross-model resources relevant to navigation:

- ZDL files
- OpenAPI files
- AsyncAPI files
- generated or hand-written source code artifacts

Related resources should be understood in two layers:

- baseline discovery: the DSL element references a resource and the service can return a normalized related-resource link
- enriched resolution: the service can also resolve, parse, or validate that resource and return deeper navigation metadata

### Proposed contract

```json
{
  "ownerId": "file:///workspace/subscriptions.zfl#flows.PaymentsFlow.systems.Subscription",
  "resources": [
    {
      "targetKind": "related-resource",
      "label": "subscription/model.zdl",
      "uri": "file:///workspace/subscription/model.zdl",
      "range": null,
      "category": "zdl",
      "iconHint": "zdl",
      "relationType": "declares-domain"
    },
    {
      "targetKind": "related-resource",
      "label": "orders/src/main/resources/apis/asyncapi.yml",
      "uri": "file:///workspace/orders/src/main/resources/apis/asyncapi.yml",
      "range": null,
      "category": "asyncapi",
      "iconHint": "asyncapi",
      "relationType": "references"
    },
    {
      "targetKind": "related-resource",
      "label": "modules/orders/src/main/resources/apis/openapi.yml",
      "uri": "file:///workspace/modules/orders/src/main/resources/apis/openapi.yml",
      "range": null,
      "category": "openapi",
      "iconHint": "openapi",
      "relationType": "references"
    }
  ]
}
```

### Guidance

- `ownerId` should be the semantic `id` / canonical semantic pointer of the owning element.
- `range` may be `null` when the target is file-level.
- URI normalization should be handled by the language-service transport layer or plugin adapter.
- The plugin should render these as related links, not as primary definitions unless `relationType` indicates direct declaration.
- Baseline related-resource links should still be returned even when the current runtime cannot parse the target spec.
- Enriched metadata such as parsed anchors, channel/operation lookup, or spec validation findings should be treated as optional and capability-dependent.
- IntelliJ and other JVM consumers may legitimately expose richer related-resource behavior than Node.js consumers if the underlying runtime supports more resolution modes.

## 9. Diagnostics contract

### Implemented today

Diagnostics are implemented and are a direct normalization of parser problems.

Current internal shape:

```json
{
  "jsonPath": "entities.Customer.fields.customerId.type",
  "range": {
    "start": { "line": 86, "character": 20 },
    "end": { "line": 86, "character": 26 }
  },
  "message": "Unknown type CustomerIdX",
  "severity": "ERROR"
}
```

### Source of truth

- The parser remains the source of truth for validation findings.
- The language server does not currently add a second semantic validation pass of its own.
- In the current implementation, all normalized diagnostics are emitted as severity `ERROR`.

### Notes on parser relation

For ZDL:

- parser problems are expected to contain `path`, `location`, and `message`
- `location` is converted from an integer array into an LSP-style range

For ZFL:

- parser problems are also normalized from parser-provided maps
- if parser location is absent, the adapter falls back to a default zero range

### Recommended LSP-facing shape

When exposed over LSP, diagnostics should preserve the semantic identity:

```json
{
  "range": {
    "start": { "line": 86, "character": 20 },
    "end": { "line": 86, "character": 26 }
  },
  "severity": 1,
  "message": "Unknown type CustomerIdX",
  "source": "zenwave-lsp",
  "code": "entities.Customer.fields.customerId.type",
  "data": {
    "jsonPath": "entities.Customer.fields.customerId.type",
    "language": "ZDL"
  }
}
```

Using the canonical semantic path in `code` or `data` is recommended because it gives the IntelliJ side a stable key for grouping, highlighting, and future quick-fix routing.

## 10. IntelliJ integration guidance

The IntelliJ plugin should consume this language service as a document service, not as a PSI mirror.

Recommended integration style:

- use file/document URIs as primary document identity
- use zero-based `line`/`character` coordinates
- send current document text or a document snapshot to the service
- treat returned semantic identifiers (`uri#<path>`) as the stable node identity
- build tool-window trees and related-resource panels from explicit contracts, not from parser class inspection

Avoid these assumptions:

- no dependency on Kotlin implementation classes from this repository
- no dependency on IntelliJ PSI element types matching language-service element types
- no assumption that hierarchy can be reconstructed from PSI alone
- no assumption that current service supports workspace-wide symbol indexing

Recommended plugin architecture:

1. Maintain editor document snapshots keyed by URI.
2. Call parse/diagnostics/hover-style services using the snapshot text.
3. Normalize all returned ranges into IntelliJ editor offsets only in the plugin layer.
4. Treat hierarchy/navigation/reference payloads as transport objects suitable for UI rendering.
5. Keep parser-specific heuristics out of the plugin when possible.

## 11. Session and document model assumptions

### Current implementation assumptions

Current `lsp-core` behavior is closest to stateless single-request processing:

- requests use raw document text
- requests do not require prior `didOpen`
- there is no built-in session document cache
- there is no built-in workspace index
- there is no multi-document graph resolution contract in the implemented API

### Effective model today

- single-file mode: yes
- open-document statefulness: no
- file URI required by core: no
- document snapshot required by caller: yes
- workspace indexing: no

### Implication for IntelliJ

The IntelliJ plugin should assume it is responsible for:

- tracking open document content
- deciding when to re-request diagnostics or hierarchy
- associating URI with text snapshots
- layering workspace awareness above the current core API if needed

## 12. Current capabilities vs planned capabilities

### Already exists

- Kotlin Multiplatform language-service core
- support for both `ZDL` and `ZFL` language selection
- parser adapters for both languages
- semantic model abstraction with:
  - `data`
  - `problems`
  - `getLocation(line, character)`
- diagnostics derived from parser problems
- hover returning semantic `jsonPath`
- test coverage proving semantic location mapping for ZDL and basic parsing coverage for ZFL

### Planned but not implemented in this repository snapshot

- actual JVM LSP server wrapper
- actual JS/Node LSP server wrapper
- definition resolution
- completion logic
- references queries
- document symbol or workspace symbol queries
- conceptual hierarchy endpoint
- related-resource endpoint for ZDL/OpenAPI/AsyncAPI/source artifacts

### Architectural intent only

- full LSP-style consumption by IntelliJ or other IDE clients
- transport-neutral service contract shared across editor ecosystems
- richer cross-resource navigation from ZFL and ZDL semantic elements
- reuse of semantic identifiers across hover, tree nodes, diagnostics, and navigation

## 13. Versioning and compatibility notes

There is no explicit protocol versioning layer implemented yet in this repository. The IntelliJ plugin should therefore assume contract evolution is still active.

Recommended compatibility strategy:

- treat current implemented API as pre-1.0
- prefer tolerant JSON/object deserialization
- ignore unknown fields
- make optional fields truly optional on the plugin side
- branch feature use by capability detection rather than by strict version assumptions

Recommended future contract policy:

- additive changes should be the default
- semantic `id` / `uri#<path>` stability should be preserved whenever possible
- existing fields should not change meaning silently
- new navigation/hierarchy payloads should include a top-level `contractVersion` once transport APIs are formalized

For the plugin:

- do not assume `definition` or `completion` will remain stubbed
- do assume payloads may become richer over time
- preserve passthrough storage for opaque metadata where possible

## 14. Open questions / ambiguities

- There is no implemented transport/server layer yet, so the exact external wire format is still open.
- There is no formal hierarchy endpoint yet; the proposed tree contract in this document is architectural guidance, not current API.
- There is no formal references endpoint yet.
- The exact semantic shape of ZFL flow nodes is not documented by a dedicated contract in the codebase; it is inferred from parser output and sample files.
- The exact URI resolution rules for relative resource paths are not yet defined.
- It is not yet specified whether future navigation/reference queries will be single-document only or workspace-aware.
- Diagnostics currently normalize to `ERROR`; warning/info mapping policy is not yet defined.
- Range fallback behavior for parser problems without locations exists for ZFL but is not yet documented as a long-term contract.
- The current core API accepts only `text` plus `language`; future transport APIs will need a URI-aware request model.
- It is not yet fully defined how canonical semantic pointers should be generated for every edge case in OpenAPI, AsyncAPI, and Avro arrays or composed schemas.

## Summary for the IntelliJ plugin agent

The safe integration assumption today is:

- consume this project as a parser-backed semantic query service
- treat `uri#<path>` as the stable semantic identity
- expect diagnostics and hover-style semantic lookup to work now
- treat hierarchy, references, related resources, completion, and definition as planned contracts rather than implemented endpoints

If a transport contract is introduced later, it should preserve the same core model:

- document text in
- URI/position/range addressing
- semantic identity via `uri#<path>`
- explicit transport objects for diagnostics, navigation, hierarchy, and related resources

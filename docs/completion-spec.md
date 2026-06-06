# LDSP Completion Specification

## 1. Purpose

This document defines autocomplete/completion behavior for the ZenWave language server and IDE integrations.

It complements:
- [navigation-spec.md](./navigation-spec.md)
- [lsp-core-spec.md](./lsp-core-spec.md)

Completion should be built on top of the same semantic graph and resolution rules used by navigation. The server should not implement a separate, unrelated completion model.

## 2. Scope

### In scope

- context-aware completion
- semantic completion for DSLs and APIs
- cross-language completion where links are already known
- completion item metadata useful for IDE rendering

### Out of scope for the first implementation wave

- snippet-heavy authoring workflows
- AI-generated completion
- ranking by usage telemetry
- rename-aware live completion updates
- code actions embedded in completion items

## 3. Relationship to navigation

Completion depends on navigation.

The same semantic graph should power:
- `Go to Definition`
- `Find References`
- hover
- completion candidates
- completion item documentation

The recommended layering is:
1. semantic identity
2. definition/reference resolution
3. cursor context classification
4. completion candidate generation
5. completion item detail/documentation

## 4. Languages and artifacts

### DSL / model languages

- ZDL
- ZFL

### API / schema languages

- OpenAPI
- AsyncAPI
- Avro

### Other artifacts

- master project manifest / architecture YAML
- inferred source code targets

### Manifest example

The current draft example of the master project manifest is:
- [zenwave-architecture.yml](C:/Users/ivangsa/workspace/arcadia-editions/arcadia-editions-docs/zenwave-architecture.yml)

The manifest/config model currently described by LDSP is captured in:
- [lsp-core-spec.md](./lsp-core-spec.md)

This completion spec assumes the manifest follows the same architecture-oriented shape used by the navigation model:
- domains
- services
- docs
- specs
- consumers

The manifest may use either:
- `domains -> services`
- `domains -> subdomains -> services`

## 5. Completion operations

## 5.1 Basic completion

Suggest valid symbols or resource names at the cursor position.

Examples:
- field type names
- event names in `withEvents`
- API names in annotations
- AsyncAPI channels/messages
- OpenAPI paths/operations where relevant

## 5.2 Rich semantic completion

Completion items may carry semantic metadata such as:
- semantic id
- kind
- detail
- documentation
- relation category
- target URI/range

This allows IDEs to render richer completion lists and previews.

## 5.3 Completion-driven navigation

Completion-driven navigation means a completion item already knows its semantic target and can expose:
- documentation preview
- semantic identity
- target definition information

This is a later layer on top of basic completion, not the first milestone.

## 6. Completion item model

The completion model should support at least:
- label
- kind
- detail
- insert text
- documentation/markdown
- semantic id
- optional target URI/range

Suggested semantic kinds:
- entity
- enum
- input
- output
- event
- service
- method
- command
- outcome
- api
- openapi-operation
- asyncapi-channel
- asyncapi-message
- avro-record
- avro-field
- repository
- documentation

## 7. Context classification

Completion should not be driven by text matching alone. The server should first classify the cursor context.

Examples of context classes:
- ZDL field type position
- ZDL service parameter type position
- ZDL return type position
- ZDL `withEvents` position
- ZDL `@asyncapi` annotation position
- ZDL REST annotation position
- ZFL `when` event position
- ZFL command position
- ZFL `call` target position
- ZFL `on Outcome` position
- OpenAPI `$ref` value position
- AsyncAPI channel/message/$ref position
- manifest service/docs/specs/consumers position

## 8. ZDL completion rules

## 8.1 In-file completion

### 8.1.1 Field types

Suggest:
- entities
- enums
- inputs
- outputs
- events

Ranking preference:
- exact prefix matches first
- local document declarations before external/inferred candidates

### 8.1.2 Service parameter / command types

Suggest:
- entities
- inputs

### 8.1.3 Service outcomes / return types

Suggest:
- entities
- outputs

### 8.1.4 `withEvents`

Suggest:
- events

### 8.1.5 API names in `apis` or annotations

Suggest:
- API identifiers declared in `apis`

## 8.2 External completion

### 8.2.1 `@asyncapi`

Possible completions:
- API names
- channels
- topics
- message names

Frozen rules:
- if `api` is present, complete channels/topics/messages from that API
- if `api` is omitted, complete from the API named `default` with role `provider`
- `channel` is the primary structural target
- `topic` is secondary metadata and may be suggested from the resolved channel or API context

Examples:

```zdl
@asyncapi({channel: "CustomerEventsChannel", topic: "customer.events"})
event CustomerEvent { ... }
```

```zdl
@asyncapi({api: OrdersAsyncAPI, channel: "OrderCreatedChannel"})
createDelivery(DeliveryInput) Delivery withEvents DeliveryStatusUpdated
```

Recommended completion flow:
1. suggest API names when editing `api`
2. suggest channels from the resolved API when editing `channel`
3. suggest topics consistent with the resolved API/channel when editing `topic`

### 8.2.2 REST/OpenAPI annotations

Possible completions:
- path templates
- HTTP operations
- operation ids

Frozen rules:
- `@rest("<basePath>")` defines the base path prefix for the service
- `@get`, `@post`, `@put`, `@delete`, and related HTTP annotations define the relative path and verb for the method
- if no explicit API selection rule exists, completion resolves against the API named `default` with role `provider`
- if `operationId` is not explicitly present on the HTTP annotation payload, it defaults to the ZDL method name
- effective path suggestions should reflect `serviceBasePath + methodRelativePath`

Example:

```zdl
@rest("/orders")
service OrdersService for (CustomerOrder) {
    @get("/{orderId}")
    getCustomerOrder(id) CustomerOrder?
}
```

This maps to:
- path: `/orders/{orderId}`
- method: `get`

### 8.2.3 Source-code targets

Potential later completion:
- inferred Java/Kotlin type or file targets

Deferred for now.

## 9. ZFL completion rules

## 9.1 In-file completion

### 9.1.1 Event positions

Suggest:
- known events in the same file
- end outcomes treated as event-like targets where applicable

### 9.1.2 Command positions

Suggest:
- known commands in the same file

### 9.1.3 Outcome positions

Suggest:
- known outcomes in the same file

TBD:
- exact `call` and `on Outcome` semantics

## 9.2 Cross-file completion

### 9.2.1 `@zdl`

Suggest:
- ZDL resources known through manifest/service bindings or local workspace resources

### 9.2.2 Command and event completion through linked ZDL

Suggest:
- ZDL service methods as command candidates
- ZDL events as event candidates

Filtering should respect the linked system/ZDL scope where possible.

## 10. OpenAPI completion rules

Suggest:
- `$ref` targets
- schema names
- path names
- operation ids
- response/request component names

Later:
- reverse-linked ZDL service suggestions where mapping rules are frozen

## 11. AsyncAPI completion rules

Suggest:
- channel names
- message names
- `$ref` targets
- payload schema names

Later:
- ZDL event/service suggestions based on frozen reverse-link rules
- consumer/producer channel suggestions through manifest service relationships

## 12. Avro completion rules

Suggest:
- named record/enum/fixed types
- field names
- field types

Later:
- AsyncAPI-linked schema suggestions
- ZDL semantic equivalence suggestions if that mapping is frozen

## 13. Manifest / architecture completion rules

The manifest should support completion for:
- domain names
- subdomain names
- service ids or service references
- docs keys
- spec types
- consumer service references
- Maven repository ids

Possible later additions:
- repository URI/resource completions
- known owned spec/resource completions

Reference example:
- [zenwave-architecture.yml](C:/Users/ivangsa/workspace/arcadia-editions/arcadia-editions-docs/zenwave-architecture.yml)

## 14. Cross-language completion matrix

| Source context | Candidate targets | Status |
|---|---|---|
| ZDL field type | entity / enum / input / output / event | defined |
| ZDL method parameter | entity / input | defined |
| ZDL method return type | entity / output | defined |
| ZDL `withEvents` | event | defined |
| ZDL `@asyncapi` | api / channel / topic / message | partially defined |
| ZDL REST annotations | OpenAPI path / operation | TBD |
| ZFL linked command | ZDL service method | partially defined |
| ZFL linked event | ZDL event | partially defined |
| OpenAPI `$ref` | schema / component target | defined |
| AsyncAPI `$ref` | schema / message target | defined |
| AsyncAPI payload | Avro schema | defined |
| manifest consumer reference | service reference | defined at high level |

## 15. Ranking and filtering

The first implementation should use simple deterministic ranking:
- exact prefix match first
- same document before external document
- semantically expected kinds before broader fallback kinds
- declared/owned resources before inferred resources

Do not introduce opaque ranking heuristics early.

## 16. Partial workspace behavior

Partial checkout and unresolved remote resources are normal.

Behavior:
- completion should still work with local/in-memory information where possible
- unresolved external resources should not suppress all suggestions
- if semantic completion is unavailable, fall back to local known declarations only

## 17. LSP surface

Completion should be exposed primarily through:
- `textDocument/completion`
- optional `completionItem/resolve` for richer documentation/details

The shared semantic model should remain in `lsp-core`.

## 18. IDE model

IDE integrations should consume the LSP completion results and remain thin.

Expected behavior:
- show basic completion quickly
- optionally lazy-load rich docs/details
- preserve semantic metadata when possible for preview/navigation

## 19. Open questions

The following still need explicit answers before full completion implementation:

1. Exact ZDL `@asyncapi` completion filtering rules
2. Exact ZDL REST/OpenAPI completion rules
3. Exact ZFL `call` semantics
4. Exact ZFL `on Outcome` semantics
5. How source-code inference should appear in completion, if at all
6. How much of reverse API-to-model completion should be supported in phase 1
7. Whether manifest service references use one canonical string syntax only

---

**Status**: This document is a first completion-focused companion to the navigation spec. It intentionally builds on the same semantic graph and marks unresolved completion semantics explicitly so they can be filled before deeper implementation.

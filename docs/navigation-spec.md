# LDSP Navigation Specification

## 1. Purpose

This document defines navigation behavior for the ZenWave language server and IDE integrations.

It covers navigation:
- inside a single file
- across files
- across languages
- from models to APIs
- from APIs back to models
- from architecture/service descriptors to owned resources
- from model elements to inferred source code representations

This document is intended to:
- freeze the first navigation model before implementation expands further
- make ambiguity explicit
- separate already-defined behavior from deferred or still-open behavior

## 2. Scope

### In scope

- `Go to Definition`
- `Find References` / usages
- click navigation
- hover-backed semantic target identification
- document structure navigation
- cross-language semantic links

### Out of scope for the first implementation wave

- code actions
- rename
- completion-driven navigation
- refactoring support
- full generated-code reverse engineering
- UI-specific behaviors outside the LSP contracts

## 3. Languages and artifacts

### DSL / model languages

- ZDL
- ZFL

### API / schema languages

- OpenAPI
- AsyncAPI
- Avro

### Other artifacts

- master project manifest / architecture YAML
- repository roots
- inferred source code files

### Manifest example

The current draft example of the master project manifest is:
- [zenwave-architecture.yml](C:/Users/ivangsa/workspace/arcadia-editions/arcadia-editions-docs/zenwave-architecture.yml)

The manifest/config model currently described by LDSP is captured in:
- [lsp-core-spec.md](./lsp-core-spec.md)

This navigation spec assumes the manifest follows the architecture-oriented shape described there:
- workspace/project-level config
- domains
- services
- docs
- specs
- consumers
- optional Maven repository configuration

The manifest may use either:
- `domains -> services`
- `domains -> subdomains -> services`

## 4. Navigation operations

The system should support these navigation operations.

### 4.1 Definition

Resolve the semantic target represented by the symbol under the cursor.

Examples:
- field type -> entity or enum definition
- ZFL command reference -> ZDL service method definition
- `@asyncapi` annotation -> AsyncAPI channel or operation
- OpenAPI `$ref` -> referenced schema

### 4.2 References

Resolve all known usages of a semantic target.

Examples:
- ZDL event -> all service methods using `withEvents`
- AsyncAPI channel -> all ZDL methods or ZFL flows referencing it
- ZDL service method -> all ZFL commands pointing to it

### 4.3 Related resource navigation

Resolve owned or associated artifacts even when the link is not a strict language definition.

Examples:
- service -> repository
- service -> docs
- service -> OpenAPI / AsyncAPI / ZDL
- event -> inferred source code file, asyncapi.yml deifnition

### 4.4 Document structure navigation

Return symbols or hierarchy nodes suitable for IDE structure views and outline navigation.

## 5. Canonical identity

All semantic elements should have stable canonical semantic identities.

### 5.1 General form

```text
<uri>#<semantic-path>
```

Examples:

```text
file:///workspace/orders.zdl#services.OrdersService.methods.cancelOrder
file:///workspace/asyncapi.yml#$.channels.OrdersChannel.publish.message
file:///workspace/subscriptions.zfl#flows.PaymentsFlow.whens.renewSubscription
```

### 5.2 Resolution principle

Navigation should resolve by semantic identity, not by textual search alone.

Text matching may be used as a fallback only where no stronger semantic binding exists.

## 6. Navigation target kinds

Navigation targets should be classified by kind.

Required target kinds:
- `definition`
- `reference`
- `related-resource`
- `source-code`
- `repository`
- `documentation`
- `api`
- `schema`

## 7. ZDL navigation rules

## 7.1 In-file navigation

### 7.1.1 Field types

A ZDL field type may resolve to:
- entity
- enum
- input
- output
- event

Examples:
- `status OrderStatus` -> `enums.OrderStatus`
- `customer Customer` -> `entities.Customer`

Remember you can always access a entity of any type (ecept events) with `allEntitiesAndEnums.<name>` and now which type is using `allEntitiesAndEnums.<name>.type`.

Definition:
- cursor on type name -> target type declaration

References:
- type declaration -> all fields using that type

Status:
- partially defined

### 7.1.2 Service method parameter / command types

A service method parameter may resolve to:
- entity
- input

Definition:
- parameter type -> target entity/input declaration

References:
- entity/input declaration -> all service methods using it as parameter type

Status:
- defined

### 7.1.3 Service outcomes / return types

A service outcome / return type may resolve to:
- entity
- output

Definition:
- return type -> target entity/output declaration

References:
- entity/output declaration -> all service methods returning it

Status:
- defined

### 7.1.4 `withEvents`

`withEvents` entries resolve to ZDL events.

Definition:
- event name in `withEvents` -> event declaration

References:
- event declaration -> all `withEvents` usages

Status:
- defined

### 7.1.5 `apis {}` block

Entries under `apis {}` define named API resources and may point to:
- OpenAPI
- AsyncAPI
- ZDL

Definition:
- API name or URI/path -> external resource target

References:
- API declaration -> all annotations referencing that API

Status:
- defined at high level
- exact matching rules still need to be frozen

## 7.2 External navigation

### 7.2.1 `@asyncapi`

Service methods and events may be annotated with `@asyncapi`.

This should navigate to an external AsyncAPI target defined in `apis`.

Possible matching dimensions:
- `api`
- `channel`
- `topic`
- message name

Definition:
- annotation or channel/topic value -> AsyncAPI target

References:
- AsyncAPI channel/message -> all ZDL methods or events referencing it

Frozen rules:
- if `@asyncapi` contains `api`, that value identifies the API declared in `apis`
- if `@asyncapi` does not contain `api`, it refers to an API named `default`
- that default API must have role `provider`
- `channel` identifies the AsyncAPI channel target
- `topic` is additional semantic metadata and may be used for validation or fallback matching

Examples:

```zdl
@asyncapi({channel: "CustomerEventsChannel", topic: "customer.events"})
event CustomerEvent { ... }
```

```zdl
asyncapi client OrdersAsyncAPI "orders/src/main/resources/apis/asyncapi.yml"

@asyncapi({api: OrdersAsyncAPI, channel: "OrderCreatedChannel"})
createDelivery(DeliveryInput) Delivery withEvents DeliveryStatusUpdated
```

Target resolution order:
1. resolve API target:
   - explicit `api`
   - otherwise `default` provider API
2. resolve `channel` inside that AsyncAPI
3. use `topic` as validation/fallback metadata where needed

### 7.2.2 REST/OpenAPI navigation

Services and methods may be annotated with:
- `@rest`
- `@get`
- `@post`
- `@put`
- `@delete`
- other HTTP method annotations

These should navigate to an OpenAPI operation in the default provider OpenAPI.

Definition:
- REST annotation -> OpenAPI path and operation

References:
- OpenAPI operation -> ZDL service method(s)

Frozen rules:
- `@rest("<basePath>")` on the service defines the base path prefix
- `@get`, `@post`, `@put`, `@delete`, and related HTTP method annotations on methods define the relative path plus HTTP verb
- the effective OpenAPI path is `serviceBasePath + methodRelativePath`
- the HTTP verb is taken from the method annotation
- if no explicit API is named, navigation targets the OpenAPI API named `default` with role `provider`
- if `operationId` is not explicitly present on the HTTP annotation payload, it defaults to the ZDL method name
- if no `default` provider OpenAPI exists, resolution falls back to the first provider OpenAPI

Example:

```zdl
@rest("/orders")
service OrdersService for (CustomerOrder) {
    @get("/{orderId}")
    getCustomerOrder(id) CustomerOrder?
}
```

This resolves to:
- path: `/orders/{orderId}`
- method: `get`
- operationId: `getCustomerOrder`

Target resolution order:
1. resolve OpenAPI API target:
   - named API if an explicit rule is added later
   - otherwise `default` provider OpenAPI
   - otherwise first provider OpenAPI
2. compute effective path from service `@rest` plus method HTTP annotation path
3. resolve HTTP operation by verb on that path

### 7.2.3 Source code inference

Entities, inputs, outputs, events, services, and related elements may have Java/Kotlin source-code representations.

These may be inferred from:
- `basePackage`
- repository layout
- target repository
- language choice (Java/Kotlin)

Definition:
- model element -> inferred source-code file/class

References:
- source-code element -> originating model element

TBD:
- exact path inference rules
- Java/Kotlin coexistence rules
- target repository selection

## 8. ZFL navigation rules

## 8.1 In-file navigation

### 8.1.1 Events

Events are:
- emitted
- subscribed to by `when`
- used in `end` outcomes

Definition:
- event usage in `when` or `end` -> event declaration or canonical semantic source in the same file

References:
- event declaration/source -> all `when`, `emits`, `end`, and related usages

TBD:
- whether all events have an explicit declaration site versus being only flow-level semantic nodes

### 8.1.2 Commands

Commands may be:
- used in `when`
- called with `call`

Definition:
- command usage -> semantic command source in the same file

References:
- command source -> all usages

TBD:
- exact `call` semantics

### 8.1.3 Outcomes

Outcomes may be listened to with `on Outcome`.

Definition:
- outcome usage -> outcome definition/source in the same file

References:
- outcome definition/source -> all usages

TBD:
- exact `on Outcome` semantics
- whether outcomes always map to events

### 8.1.4 End outcomes

End outcomes are treated as events on the same file.

Definition:
- end outcome usage -> same-file end outcome semantic node

References:
- end outcome semantic node -> all usages

TBD:
- whether end outcomes are always event-like or only outcome-like

## 8.2 Cross-file navigation

### 8.2.1 `@zdl`

Systems have a `@zdl` annotation pointing to the ZDL representing the service/domain.

Definition:
- `@zdl` value -> linked ZDL document

References:
- ZDL document/service/event/method -> all ZFL systems or flow nodes using it

Status:
- defined

### 8.2.2 Commands and events through linked ZDL

Commands and events referenced in ZFL should navigate through the linked ZDL model.

Examples:
- ZFL command -> ZDL service method
- ZFL event -> ZDL event

References:
- ZDL service method/event -> all matching ZFL usages

TBD:
- exact scoping rules when multiple linked systems/ZDLs are in one file
- fallback behavior when names collide

### 8.2.3 External APIs through linked ZDL

ZFL commands and events may also navigate to external APIs through the linked ZDL and its API annotations.

Examples:
- ZFL command -> ZDL method -> AsyncAPI/OpenAPI target
- ZFL event -> ZDL event -> AsyncAPI target

Status:
- intended
- second phase after ZFL -> ZDL is stable

## 9. OpenAPI navigation rules

## 9.1 In-file

- path -> operations
- operation -> parameters / request body / responses
- schema -> referenced schema via `$ref`
- cross-file `$ref` -> external schema target

Definition:
- `$ref` -> referenced node

References:
- schema/operation -> all referencing nodes

Status:
- mostly defined

## 9.2 Reverse navigation to models

OpenAPI operations may reverse-link to:
- ZDL services
- ZDL service methods

TBD:
- exact matching rules from ZDL REST annotations to OpenAPI operations

## 10. AsyncAPI navigation rules

## 10.1 In-file

- channel -> publish/subscribe operations
- operation -> message
- message -> payload
- `$ref` -> referenced node

Definition:
- `$ref` or payload link -> referenced message/schema target

References:
- message/channel/schema -> all referencing nodes

Status:
- mostly defined

## 10.2 Reverse navigation to models

AsyncAPI should reverse-link to:
- ZDL events
- ZDL service methods with `@asyncapi`
- ZFL commands/events through linked ZDL

TBD:
- exact matching precedence for channel/topic/message/api

## 10.3 AsyncAPI client navigation

Consumer-side `asyncapi-client.yml` is expected to reference producer-side AsyncAPI channels.

This is important, but deferred from the first navigation implementation wave.

Deferred:
- validation of client channel references against producer channels
- references from producer channels to consumer client specs

## 11. Avro navigation rules

## 11.1 In-file

- record -> fields
- field type -> primitive or named type
- named type -> target type definition

Definition:
- field type -> record/enum/fixed target

References:
- type declaration -> all fields/messages using it

Status:
- defined

## 11.2 Cross-file

Avro is primarily linked through AsyncAPI payload references.

Definition:
- AsyncAPI payload -> Avro schema

References:
- Avro schema -> AsyncAPI messages using it

Potential later link:
- Avro schema -> ZDL event/input/output by semantic convention

TBD:
- exact rules for Avro <-> ZDL semantic equivalence

## 12. Project manifest / architecture navigation

The manifest should support navigation from:
- service -> repository
- service -> docs
- service -> owned ZDL/OpenAPI/AsyncAPI/AsyncAPI-client specs

This applies whether the service is declared:
- directly under a domain
- under a domain subdomain

Later:
- reverse navigation from owned spec back to owning service
- producer/consumer validation and navigation graph

Status:
- service -> resource links in scope
- graph enrichment deferred

Reference example:
- [zenwave-architecture.yml](C:/Users/ivangsa/workspace/arcadia-editions/arcadia-editions-docs/zenwave-architecture.yml)

## 13. Cross-language navigation matrix

This matrix summarizes intended links.

| Source | Target | Status |
|---|---|---|
| ZDL field type | entity / enum / input / output / event | defined |
| ZDL service parameter | entity / input | defined |
| ZDL service return type | entity / output | defined |
| ZDL `withEvents` | ZDL event | defined |
| ZDL `apis` entry | OpenAPI / AsyncAPI / ZDL resource | defined at high level |
| ZDL `@asyncapi` | AsyncAPI channel/message/api target | defined |
| ZDL REST annotations | OpenAPI operation | defined |
| ZDL model element | inferred Java/Kotlin source code | TBD |
| ZFL `@zdl` | linked ZDL file | defined |
| ZFL command | linked ZDL service method | partially defined |
| ZFL event | linked ZDL event | partially defined |
| ZFL command/event | linked external APIs through ZDL | deferred second phase |
| OpenAPI `$ref` | OpenAPI schema/target | defined |
| AsyncAPI `$ref` | AsyncAPI schema/message/target | defined |
| AsyncAPI payload | Avro schema | defined |
| AsyncAPI channel/message | ZDL method/event | partially defined |
| Avro schema | AsyncAPI payload usages | defined |
| manifest service | repository/docs/specs | defined at high level |

## 14. Ambiguity and precedence rules

When multiple navigation targets are possible, the server must apply explicit precedence rules.

The first implementation should avoid guessing when no rule is frozen.

### 14.1 Required precedence definitions

Still needed:
- ZFL command/event scoping when multiple systems or linked ZDLs match
- source-code inference precedence when multiple repos/languages match

### 14.2 Behavior when ambiguous

When ambiguity remains after applying frozen rules:
- return multiple navigation targets
- annotate them with category/relation metadata
- do not silently pick one arbitrary target

## 15. Missing targets and partial workspace behavior

Partial checkout and remote/resource absence are normal conditions.

Behavior:
- unresolved local or remote target should not crash the server
- diagnostics should be emitted where appropriate
- navigation may degrade to file-level related-resource links when semantic target resolution is unavailable

## 16. LSP surface

The navigation model should be exposed primarily through standard LSP operations:
- `textDocument/definition`
- `textDocument/references`
- `textDocument/documentSymbol`
- `workspace/symbol`
- `textDocument/hover`

Custom requests may still be used for richer hierarchy or related-resource views.

## 17. IDE model

IDE integrations should remain thin.

Expected behavior:
- click navigation uses `definition`
- find usages uses `references`
- outline/structure uses `documentSymbol` or hierarchy
- richer related links may use custom requests later

The semantic graph belongs in `lsp-core`, not in IntelliJ- or VSCode-specific code.

## 18. Open questions

The following need explicit answers before full implementation:

1. What are the exact ZFL `call` semantics?
2. What are the exact ZFL `on Outcome` semantics?
3. Are ZFL end outcomes always event-like targets?
4. What are the exact Java/Kotlin source-code inference rules?
5. How is the target repository for inferred source code selected?
6. How do we map Avro schemas back to ZDL elements, if at all?
7. What are the exact reverse-navigation expectations from OpenAPI/AsyncAPI back to ZDL/ZFL?

---

**Status**: This document is intentionally a first structured draft. It freezes the navigation space, identifies what can already be implemented, and marks unresolved semantics explicitly so they can be filled before deeper implementation. 

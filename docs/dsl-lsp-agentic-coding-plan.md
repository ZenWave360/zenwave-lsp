# ZDL + ZFL Shared LSP Agentic Coding Plan

## Status

This document is now historical context.

The active execution record is `docs/agentic-coding-plan.md`. That newer plan expanded the scope from shared ZDL/ZFL services to a single server with first-class support for:

- ZDL
- ZFL
- OpenAPI
- AsyncAPI
- Avro

Implementation status as of 2026-04-19:

- the shared-core architecture described here was adopted
- the JVM LSP transport is implemented in `lsp-jvm`
- cross-language navigation and shared semantic-pointer identity are implemented in `lsp-core`

Use this document for rationale and early decomposition, but prefer `agentic-coding-plan.md` and `lsp-architecture-and-contracts.md` for the current architecture.

## Goal

This document captures the earlier shared-platform direction for ZDL and ZFL.

The current execution plan is now `docs/agentic-coding-plan.md`, which expands the scope to a single server with first-class support for:

- ZDL
- ZFL
- OpenAPI
- AsyncAPI
- Avro

The architectural principles in this document still apply, but where this file conflicts with `agentic-coding-plan.md`, the newer plan is the source of truth.

Evolve the current work-in-progress language server into a reusable shared language-service platform for:

- **ZFL**: business flow / Event Storming flow language
- **ZDL**: context / domain modeling language

The server must:

- support both languages in the same language-service platform
- expose shared document, navigation, diagnostics, and hierarchy contracts
- remain transport-neutral at the core
- later support:
  - IntelliJ
  - VS Code
  - future web tooling

This plan assumes a **shared core, language-specific adapters, thin transports** architecture.

---

## Key Architectural Principle

Build the system in layers:

1. **Shared core contracts**
2. **Shared document/session model**
3. **Language-specific semantic adapters**
   - ZFL
   - ZDL
4. **Shared service façade**
5. **Transport adapters**
   - JVM / LSP4J
   - JS / Node / VS Code later
6. **Editor integrations**

The core must not become:
- IntelliJ-specific
- ZFL-only
- ZDL-only
- JVM-only

The source of truth should be:
- shared semantic contracts
- shared document model
- language-specific semantic extraction behind stable interfaces

---

## Why a Shared Server Makes Sense

A shared LSP server makes sense because ZFL and ZDL are related DSLs with overlapping IDE needs:

Shared concerns:
- diagnostics
- hover / semantic lookup
- definition
- conceptual hierarchy
- related resources
- source locations
- document lifecycle
- transport protocols

Language-specific concerns:
- parser adapters
- semantic model extraction
- hierarchy building rules
- definition/reference resolution rules
- related resource discovery rules

Recommended architecture:
- **shared platform**
- **separate language modules**
- **common public contracts**

This avoids:
- duplicated transport code
- duplicated document/session management
- duplicated diagnostics plumbing
- inconsistent navigation contracts across languages

---

## Current State Summary

### Already available
- Kotlin Multiplatform `lsp-core`
- parser adapters for ZFL and ZDL/ZDL-style domain parsing
- diagnostics
- hover-like lookup via `jsonPath`
- semantic model abstraction
- synchronous text-based parsing flow

### Missing or incomplete
- explicit shared core contracts
- conceptual hierarchy API
- definition resolution
- references
- related resources
- document/session lifecycle
- caching
- runnable JVM LSP transport
- runnable JS/Node LSP transport

### Important cross-language constraint
The server must support **both ZFL and ZDL** without forcing one language's semantics onto the other.

That means:
- shared contracts
- language-specific implementations behind the same service interfaces
- capability flags where features differ
- language-aware hierarchy and navigation rules

---

## Target Architecture

## 1. Shared Core Layer

Owns:
- document abstractions
- source locations
- positions/ranges
- semantic IDs
- diagnostics DTOs
- hierarchy DTOs
- navigation DTOs
- related-resource DTOs
- service interfaces
- capability model

Does not own:
- ZFL-specific semantic rules
- ZDL-specific semantic rules
- transport implementation details
- editor-specific behavior

---

## 2. Language Modules

### ZFL module
Owns:
- parser adapter for ZFL
- semantic extraction for flows
- hierarchy rules for systems, services, commands, events, policies, starts, ends
- definition/reference rules for flow semantics
- related resources from flow references

### ZDL module
Owns:
- parser adapter for ZDL
- semantic extraction for bounded contexts, entities, aggregates, services, events, APIs
- hierarchy rules for domain/context structures
- definition/reference rules for domain semantics
- related resources from API/spec/context references

---

## 3. Shared Service Façade

Provides a stable API such as:

- diagnostics(document)
- semanticLookup(document, position)
- hierarchy(document)
- definition(document, position)
- relatedResources(document, semanticId)

The façade delegates to the proper language module based on:
- explicit language
- or document type detection if introduced later

---

## 4. Transport Layer

Thin adapters only:
- JVM / LSP4J
- JS / Node / VS Code later

Transport should:
- map wire protocol objects
- manage didOpen/didChange/didClose
- call shared service façade
- publish diagnostics
- avoid implementing semantics

---

## Recommended Implementation Strategy

## Phase 1 — Stabilize Shared Public Core Contracts

### Goal
Make `lsp-core` the shared semantic source of truth for both ZFL and ZDL.

### Tasks
- Define explicit public service interfaces for:
  - diagnostics
  - hover / semantic lookup
  - conceptual hierarchy
  - definition
  - related resources
- Define shared DTOs for:
  - `DocumentRef`
  - `DocumentSnapshot`
  - `LanguageId`
  - `SourceLocation`
  - `Range`
  - `Position`
  - `SemanticId`
  - `HierarchyNode`
  - `NavigationTarget`
  - `RelatedResource`
  - `Diagnostic`
  - `ServerCapabilities`
- Standardize `jsonPath` as the current semantic ID where applicable
- Document which fields are:
  - shared
  - language-specific extensions
- Mark APIs as:
  - implemented
  - partial
  - stub
  - planned

### Deliverable
A clean, shared core API that both languages and both editor ecosystems can rely on.

---

## Phase 2 — Introduce Explicit Language Modules

### Goal
Separate shared platform concerns from language-specific logic.

### Tasks
- Create or formalize:
  - `zfl-language-service`
  - `zdm-language-service`
  or equivalent internal modules/packages
- Move parser-specific semantic extraction behind per-language adapters
- Define per-language strategy interfaces for:
  - hierarchy building
  - definition resolution
  - related resource discovery
- Add language capability descriptors

### Deliverable
A shared platform with clean language boundaries.

### Why this matters
Without this, one language will dominate the design and the other will become awkward.

---

## Phase 3 — Implement Conceptual Hierarchy for Both Languages

### Goal
Expose hierarchy as a first-class API for both ZFL and ZDL.

### Tasks
- Implement hierarchy for ZFL:
  - flow
  - system
  - service
  - command
  - event
  - policy
  - start/end or equivalents
- Implement hierarchy for ZDL:
  - context
  - aggregate
  - entity
  - enum
  - relationship
  - service
  - event
  - API/spec references where relevant
- Keep the DTO shared
- Keep `kind` language-aware but not editor-specific
- Add tests for both languages

### Deliverable
A shared hierarchy contract with language-specific builders.

---

## Phase 4 — Implement Definition Resolution for Both Languages

### Goal
Make basic navigation work across both DSLs.

### Tasks
- Implement same-file definition for ZFL
- Implement same-file definition for ZDL
- Resolve direct references where parser data already makes it possible
- Return shared `NavigationTarget`
- Add tests per language

### Do not do yet
- full workspace indexing
- advanced cross-project resolution
- speculative inference

### Deliverable
Reliable first definition support across both languages.

---

## Phase 5 — Design API / YAML Parsing Adapter

### Goal
Define a shared, transport-neutral contract for resolving and parsing external OpenAPI / AsyncAPI YAML resources referenced from ZFL and ZDL documents.

### Tasks
- Define a platform-neutral adapter interface in `lsp-core` for API/spec resolution and parsing
- Keep parser integration platform-specific:
  - JVM implementation in `lsp-jvm`
  - JS / Node implementation in `lsp-js`
- Define normalized DTOs for:
  - spec identity
  - resolved resource URI
  - parse / validation outcome
  - parsed navigation anchors where available
- Define capability flags for platform-specific support such as:
  - local file resolution
  - remote HTTP / HTTPS resolution
  - authenticated HTTP resolution
  - classpath resource resolution
  - parsed spec navigation
  - spec validation
- Require graceful degradation when a platform cannot resolve or parse a referenced spec
- Clarify that related resources has two levels:
  - baseline resource discovery from DSL references
  - enriched resource parsing / validation when platform support exists
- Document how relative resource resolution depends on document URI / workspace context

### Deliverable
A shared adapter design that keeps `lsp-core` platform-neutral while allowing richer JVM behavior and narrower Node.js behavior without contract divergence.

---

## Phase 6 — Implement Related Resources for Both Languages

### Goal
Expose non-code navigation targets from both DSLs.

### Tasks
- Add `relatedResources(document, semanticId)` API
- Support the easiest explicit references first
- Return baseline related-resource links even when spec parsing is unavailable on the current platform
- Enrich related-resource results with parsed spec metadata and deeper navigation targets only when adapter capabilities allow it

For ZFL:
- flow -> referenced ZDL file
- flow -> referenced OpenAPI / AsyncAPI resources

For ZDL:
- context/model -> referenced APIs/spec files
- event/service -> explicit messaging/API resources where available

- Return file-level targets when precise range is unknown
- Add tests for both languages

### Deliverable
A shared related-resource contract usable by IntelliJ and VS Code.

---

## Phase 7 — Introduce Shared Document/Session Model

### Goal
Move from stateless text-only calls to a proper shared document-oriented service.

### Tasks
- Add shared document types:
  - URI
  - text
  - version
  - language
- Implement lifecycle methods:
  - open document
  - update document
  - close document
  - get snapshot
- Add optional cache of parsed/semantic models
- Define invalidation rules
- Ensure the session model is language-agnostic

### Deliverable
A reusable document/session service layer for all transports and both languages.

---

## Phase 8 — Improve Diagnostics for Both Languages

### Goal
Make diagnostics editor-grade and consistent.

### Tasks
- Normalize severities properly
- Include URI in all diagnostics
- Preserve semantic ID in code/data
- Keep language identifier in diagnostic metadata
- Distinguish parser-origin and semantic-origin diagnostics if needed
- Add tests for both languages

### Deliverable
Diagnostics that work consistently in IntelliJ, LSP clients, and VS Code.

---

## Phase 9 — Add JVM LSP Transport

### Goal
Create the first real transport adapter for immediate development use.

### Tasks
- Build a thin JVM transport wrapper using LSP4J
- Implement:
  - initialize
  - didOpen
  - didChange
  - didClose
  - hover
  - publishDiagnostics
  - definition
- Optionally add:
  - documentSymbol
  - custom request for conceptual hierarchy
- Delegate all semantics to the shared service façade

### Deliverable
A runnable JVM LSP server serving both ZFL and ZDL.

### Why JVM first
Immediate tooling pressure is on IntelliJ and JVM development is the shortest path.

---

## Phase 10 — Add JS/Node Transport for VS Code

### Goal
Prepare real reuse in a future VS Code extension.

### Tasks
- Create a JS/Node transport adapter
- Reuse the same shared core contracts
- Keep language dispatch in shared service façade, not in transport
- Validate extension hosting constraints
- Avoid re-implementing semantics in TypeScript if Kotlin/JS is retained

### Deliverable
A transport path for VS Code reuse across both ZFL and ZDL.

---

## Phase 11 — Editor Integration Strategy

### IntelliJ
Use the core service or JVM transport to provide:
- diagnostics
- hover
- definition
- conceptual hierarchy
- related resources

for both:
- ZFL editors
- ZDL editors

### VS Code
Use the JS/Node transport or a compatible wrapper to provide:
- diagnostics
- hover
- definition
- tree/explorer views
- related resources

for both languages.

### Rule
Editor clients should consume shared contracts and remain language-aware only where necessary.

---

## Recommended Parallel Work

These can be worked on in parallel with relatively low merge risk.

### Track A — Shared Core Contracts
- DTOs
- service interfaces
- capability model
- status docs

### Track B — ZFL Hierarchy + Definition
- semantic model -> hierarchy
- same-file definition
- tests

### Track C — ZDL Hierarchy + Definition
- semantic model -> hierarchy
- same-file definition
- tests

### Track D — JVM Transport Skeleton
- initialize
- didOpen/didChange/didClose
- DTO mapping to LSP4J

---

## Work That Should Not Be Parallelized Early

Avoid parallelizing these too soon:
- shared document/session store refactor
- broad shared service interface rewrites
- parser adapter redesign across both languages
- competing transport abstractions

These create the most merge pain.

---

## Recommended Order of Execution

1. Shared core contracts
2. Explicit language modules
3. Conceptual hierarchy for ZFL + ZDL
4. Definition for ZFL + ZDL
5. API / YAML parsing adapter design
6. Related resources for ZFL + ZDL
7. Shared document/session model
8. Diagnostics polish
9. JVM transport
10. JS/Node transport
11. IntelliJ + VS Code integrations

---

## Design Rules for Shared ZDL + ZFL Support

- Keep core APIs document-based, not editor-based
- Use URI + range everywhere
- Keep DTOs serialization-friendly
- Avoid IntelliJ-specific types in shared modules
- Avoid forcing ZFL hierarchy shapes onto ZDL, or vice versa
- Use shared contracts with language-aware `kind` and metadata
- Prefer capability discovery over rigid version coupling
- Keep transport adapters thin

---

## Suggested First Agent Task

Start with:

Stabilize `lsp-core` as a shared transport-neutral document semantic service for both ZFL and ZDL by introducing explicit public DTOs and service interfaces for diagnostics, conceptual hierarchy, definition, and related resources. Standardize `jsonPath` as the current semantic identity where applicable. Do not implement transport yet.

---

## Suggested Second Agent Task

Then:

Introduce explicit language-specific semantic adapters/builders for ZFL and ZDL behind a shared service façade. Implement conceptual hierarchy first for both languages, with tests.

---

## Suggested Third Agent Task

Then:

Implement same-file definition resolution and related resource discovery for the easiest supported cases in both ZFL and ZDL, with tests. Keep the core transport-neutral.

---

## Suggested Fourth Agent Task

Then:

Add a JVM LSP wrapper using LSP4J for initialize, didOpen, didChange, didClose, hover, diagnostics, and definition, delegating all semantics to the shared service façade.

---

## Final Recommendation

Yes, the plan should be updated to reflect that the same language server will support both ZDL and ZFL.

That changes the architecture in an important way:

- not one language-specific LSP with another language bolted on
- but a shared language-service platform with per-language semantic adapters

This gives you:
- cleaner reuse
- lower duplication
- better long-term compatibility across IntelliJ and VS Code
- a more stable transport and editor integration model

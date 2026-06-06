# LDSP - Language Domain Server Protocol Specification

## 1. Overview

**LDSP** (Language Domain Server Protocol) is a specialized Language Server Protocol implementation for enterprise architecture modeling, API specifications, domain-driven design navigation, and resource-backed cross-references.

It enables navigation across heterogeneous artifacts:
- Master Architecture YAML
- Domain Models
- OpenAPI / AsyncAPI specifications
- Generated code
- Local and remote repositories
- Maven artifacts (JARs containing schemas and templates)
- Classpath resources
- Schema Registries

### Current scope

The current implementation focus is:
- `lsp-core` as the shared Kotlin Multiplatform semantic core
- `lsp-jvm` as the first transport/runtime target
- Master Architecture YAML parsing and validation
- Resource resolution and loading
- Cross-language navigation between architecture YAML, ZDL, OpenAPI, AsyncAPI, and Avro

Out of immediate scope:
- full VS Code Web / Wasm support
- producer/consumer semantic validation between `asyncapi-client.yml` and producer `asyncapi.yml`
- architecture-driven cross-reference features that depend on that validation

## 2. Execution Environments

The LDSP Server must support these runtimes:

| Environment       | Technology   | Execution Model    | Current Priority |
|-------------------|--------------|--------------------|------------------|
| JVM               | Kotlin/JVM   | Standard process   | Active           |
| Node.js Desktop   | Kotlin/JS    | Node.js process    | Supported target |
| VS Code Web       | Kotlin/Wasm  | Web Worker + WASI  | Later            |

## 3. Core Architecture

- **Core Module**: Pure/shared Kotlin containing:
  - Master Architecture YAML parsing
  - YAML + JSON + DSL parsing
  - Variable expansion
  - Resource resolution contracts
  - Cross-navigation logic
  - Symbol and relationship indexing

- **Platform Adapters**: Thin wrappers implementing:
  - LSP transport
  - Filesystem access
  - Classpath access
  - Maven artifact access
  - Optional remote resource access

## 4. Master Architecture YAML

The master YAML is a first-class input/configuration artifact for the language server.

### 4.1 Workspace config model

The LSP does not infer the master YAML automatically from the workspace root.

The preferred local workspace config file is:

```yaml
# .zenwave/config.yml
project-manifest: my-docs/master.yml
```

This file is a local workspace input, not necessarily a versioned project artifact.

Typical usage:
- the architecture manifest itself is versioned in a docs repository
- `.zenwave/config.yml` points to that manifest from the developer's local checkout topology
- teams may check out only the repositories they need

IDE settings may still exist as overrides, but they are not the primary shared contract.

Recommended precedence:
1. IDE explicit override
2. `.zenwave/config.yml`
3. no manifest configured

### 4.2 LSP startup contract

The IDE client starts the LSP server and passes resolved configuration in `initializationOptions`.

Example shape:

```json
{
  "zenwave": {
    "configUri": "file:///workspace/.zenwave/config.yml",
    "projectManifestUri": "file:///workspace/my-docs/master.yml"
  }
}
```

The server should not guess the manifest location on its own when the client can provide it explicitly.

### 4.3 Manifest role

The master YAML acts as:
- workspace architecture root
- resource resolution context
- Maven repository configuration source
- cross-service navigation graph root

### 4.4 Supported master YAML concerns

The master YAML is expected to describe:
- `config`
- `config.properties`
- `maven.repositories`
- `domains`
- `services`
- `docs`
- `specs`
- `consumers`

### 4.4.1 Domain hierarchy variants

The manifest must support both of these shapes:

```yaml
domains:
  orders:
    services:
      orders-checkout:
        ...
```

and:

```yaml
domains:
  orders:
    subdomains:
      checkout:
        services:
          orders-checkout:
            ...
```

Rules:
- `subdomains` is optional
- a domain may declare `services` directly
- a domain may declare `subdomains`
- the parser may normalize both forms into a single internal model
- user-facing hierarchy should preserve the original structure where possible

Service reference syntax and navigation must therefore support both:
- `domain/service`
- `domain/subdomain/service`

### 4.5 Remote manifest support

The `project-manifest` may point to:
- a local file path
- a local file URI
- a Git-backed remote resource
- another supported resource URI

The architecture manifest does not need to be checked out locally if the configured resolver can load it remotely.

### 4.6 Consumer references

`consumers` are semantic service references, not necessarily JSON Pointer `$ref` values.

A consumer entry identifies another service in the architecture graph using a domain/subdomain/service reference model.

The server should normalize these references to a canonical internal service identity and support navigation/validation against that identity.

## 5. Variable Expansion

The master YAML supports variable interpolation using Handlebars-style placeholders such as `{{root}}`.

### 5.1 Semantics

- Variable expansion is limited to value replacement, not full template execution.
- Variables are resolved from property maps, primarily `config.properties`, with room for client/workspace-provided overrides later.
- Expansion happens before resource resolution.
- Expanded values may already contain a scheme such as:
  - `file://`
  - `classpath:`
  - `zenwave://maven/...`
- Expanded values may also be relative paths.

Example:

```yaml
config:
  properties:
    root: file:///workspace/arcadia
```

Then:

```yaml
repository: "{{root}}/orders-checkout-api"
```

must resolve as a resource-aware join, not a filesystem-only string concatenation.

The variable name itself has no special semantics. `root` is only an example. Different variables may point to different protocols or resource bases.

### 5.2 Requirements

- Unknown variables must produce diagnostics.
- Cyclic variable references must produce diagnostics.
- Expansion must preserve enough metadata to map diagnostics back to the original YAML node.
- Variable expansion must not embed a full template engine with conditionals, loops, or helpers.
- Handlebars syntax may be reused for placeholders, but the implementation should be a strict ZenWave interpolator, not a full Handlebars engine.

## 6. Resource Resolution

The server must expose a unified `ResourceResolver` above concrete loaders.

### 6.1 Responsibilities

The resolver is responsible for:
- applying variable expansion
- resolving relative paths against a base resource
- detecting scheme
- delegating to the appropriate loader
- normalizing resolved resource URIs
- surfacing diagnostics and degradation

### 6.2 Supported schemes

The server must support these schemes:
- `file://` for local files
- plain relative/local paths
- `git://` for remote Git repositories
- `registry://` for schema registries
- `classpath:` for classpath-style resource lookup
- `zenwave://maven/...` for resources inside Maven artifacts

`classpath:` is supported in:
- JVM
- Node.js

It is not JVM-only.

### 6.4 Relative resolution rules

Relative resolution depends on the context:
- `project-manifest` in `.zenwave/config.yml` resolves relative to the workspace root, not relative to the `.zenwave` directory itself
- relative references inside the manifest resolve relative to the manifest resource base
- resource children under a resolved service repository/spec base use resource-aware path appending, preserving the target protocol

### 6.3 Maven integration

Maven-backed resources are resolved through the `ResourceResolver` and delegated to `MavenResourceLoader`.

The detailed Maven behavior is specified in:
- [resource-loader-spec.md](./resource-loader-spec.md)

## 7. Key Capabilities

### 7.1 Standard LSP Features

Planned/required standard features:
- `textDocument/didOpen`
- `textDocument/didChange`
- `textDocument/didSave`
- `textDocument/hover`
- `textDocument/definition`
- `textDocument/references`
- `textDocument/documentSymbol`
- `workspace/symbol`
- `workspace/executeCommand`

### 7.2 Architecture-first implementation order

The near-term implementation priority is:
1. Workspace config parsing and manifest URI handling
2. Master YAML parsing
3. Variable expansion
4. Resource resolver contracts
5. File / classpath / Maven loading
6. Resource binding from architecture services to docs/specs/repositories
7. Architecture diagnostics, hover, definition, and symbols

Deferred until later:
8. architecture-driven cross-reference graph enrichment
9. producer/consumer `asyncapi-client.yml` versus producer `asyncapi.yml` validation

### 7.3 Custom LDSP Methods

The protocol may expose custom methods such as:
- `ldsp/resolveCrossReference`
- `ldsp/fetchRemoteResource`
- `ldsp/getArchitectureMap`
- `ldsp/searchModels`

These remain valid design targets, but they are not required before the architecture parsing and resource loading phases are complete.

## 8. Architecture Resource Binding

For each service declared in the master YAML, the server should be able to resolve and bind:
- repository root
- docs entries
- ZDL spec
- OpenAPI spec
- AsyncAPI spec
- AsyncAPI client spec

Resolution rules:
- paths are resolved relative to the service repository unless already absolute/schemed
- repository may itself come from variable-expanded URI-like values
- bound resources become navigation targets and future cross-reference/indexing inputs

## 9. Non-Functional Requirements

- **Performance**: Fast incremental parsing and resource resolution.
- **Offline Support**: Graceful degradation when remote resources are unavailable.
- **Determinism**: Variable expansion and resource resolution must be stable and reproducible.
- **Security**: No arbitrary template execution in variable replacement.
- **Cacheability**: Maven and other remote-backed resources must support cache-aware loading.

## 10. Technology Stack

- **Language**: Kotlin Multiplatform
- **Shared Core**: Kotlin common code where possible
- **Primary runtime**: JVM
- **Secondary runtime**: Kotlin/JS for Node.js
- **Serialization**: kotlinx.serialization
- **YAML Parsing**: multiplatform YAML/JSON parsing infrastructure
- **LSP transport**:
  - JVM: LSP4J
  - Node.js: later transport adapter

---

**Status**: This specification now reflects the current architecture-first implementation path: master YAML as LSP input, variable expansion, resource resolution, and resource binding before deeper architecture cross-reference validation.

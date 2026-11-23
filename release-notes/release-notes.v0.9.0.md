## What's Changed

First release, **0.9.0**, of the ZenWave language server: `lsp-core`, `lsp-jvm` and `lsp-js` (`io.zenwave360.language`) on Maven Central, and `@zenwave360/lsp-js` on npm. It serves ZDL, ZFL and `.zw` documents and the ZenWave architecture manifest, over stdio (JVM), Node IPC and a Web Worker (JS).

### Language features

- Diagnostics, hover, outline, document symbols, definition, references, completion and code actions for ZDL and ZFL.
- Navigation across files: a ZFL command or event goes to its system's ZDL; `@zdl` accepts a list of locations, tried in order; ZDL goes to AsyncAPI channels and OpenAPI operations.
- A background reference index of local workspace files, kept on close and followed on disk, with `zenwave/indexStatus`.
- Rules: hover, outline, definition, completion in `@rules(...)`, qualified rule names, quick fixes from `policies` and `@policy`, warnings for unknown rules.

### Previews and visualizations

- `zenwave/preview` answers by mode: Markdown pages for ZDL and `.zw` documents, Mermaid diagrams grouped by kind.
- `zenwave/eventFlowViews`: EventFlow diagrams, lanes by system or service, saved positions, and the variants of a flow as a tree grouped by outcome.
- `zenwave/forwardReferences`, `zenwave/reverseReferences` and `zenwave/symbolAt` for the editors.
- Organize the `systems` block of a flow, in full or additive mode.

### Architecture manifest and resources

- Manifest hierarchy with domains, subdomains, artifacts, docs and consumers; workspace queries.
- The manifest may be local or an http(s) address; `zenwave:` addresses are resolved with a declared-only check, allowed domains from initialization options, guarded `$ref` reading and redacted errors.
- `zenwave/statResource` and `zenwave/readResource` on both servers.

### Dependencies

- `dsl-kotlin`, `dsl-views` and `dsl-docs` 1.11.0; `architecture-manifest` and `workspace-runtime` 0.9.6; `json-schema-ref-parser-kmp` 0.9.24.

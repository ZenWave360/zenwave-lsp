# @zenwave360/lsp-js

The ZenWave language server for JavaScript runtimes: ZDL, ZFL, the architecture manifest, AsyncAPI,
OpenAPI and Avro. It is the Kotlin `lsp-core` compiled to JavaScript, speaking the Language Server
Protocol through `vscode-languageserver`.

The package ships two self-contained entry points. Neither needs `node_modules` at run time and
neither needs a bundler.

| Export | File | Runtime | Transport |
| --- | --- | --- | --- |
| `@zenwave360/lsp-js/node` | `dist/node/zenwave-lsp-server.js` | Node child process (CommonJS, Node 18+) | child-process IPC |
| `@zenwave360/lsp-js/worker` | `dist/browser/zenwave-lsp-worker.js` | browser Web Worker (classic script) | `postMessage` |

The worker bundle uses no Node API. The build fails if one reaches it.

## Desktop: Node child process over IPC

```ts
import { LanguageClient, TransportKind } from 'vscode-languageclient/node';

const module = require.resolve('@zenwave360/lsp-js/node'); // or a path inside your extension
const client = new LanguageClient('zenwave', 'ZenWave', {
  run: { module, transport: TransportKind.ipc },
  debug: { module, transport: TransportKind.ipc },
}, clientOptions);
```

The server uses IPC when it is forked without transport arguments. It also honours `--node-ipc`,
`--stdio`, `--socket=<port>` and `--pipe=<name>`.

## Browser: Web Worker

```ts
import { LanguageClient } from 'vscode-languageclient/browser';

const worker = new Worker(workerUrl); // URL of dist/browser/zenwave-lsp-worker.js
const client = new LanguageClient('zenwave', 'ZenWave', clientOptions, worker);
```

The worker connects with `BrowserMessageReader(self)` and `BrowserMessageWriter(self)`, so the client
talks to it through the `Worker` object. In the browser there is no filesystem. Documents the client
opens arrive through `textDocument/didOpen`, and other content can only be reached over HTTP.

## Capabilities

`initialize` returns the standard capabilities the language modules support: incremental text sync,
hover, definition, references, document symbols and formatting. `capabilities.experimental` also carries:

- `moduleSelectors`: `{ languageId, extensions }[]`, one entry per language module;
- `customRequests`: `string[]`, the `zenwave/*` requests this server answers. The list is built from the
  server's handler registration, so a client can rely on it. A request that is not listed is answered
  with JSON-RPC `-32601`. lsp-jvm additionally exposes workspace concept and status requests.
- `previewModes`: `true` when `zenwave/preview` accepts its additive `mode` parameter and grouped diagram representations.
- `organizeModes`: `true` when `zenwave/organizeZflServices` accepts `mode` (`full`, the default, or `additive`).

## Custom requests

| Method | Params | Result |
| --- | --- | --- |
| `zenwave/hierarchy` | `{ uri }` | hierarchy nodes `{ id, label, kind, language, sourceUri, sourceRange, children, relatedResources, uiHints, viewNodeIds }[]` |
| `zenwave/forwardReferences`, `zenwave/reverseReferences` | `{ uri, semanticId }` | navigation targets |
| `zenwave/organizeZflServices` | `{ uri, mode?: "full" \| "additive" }` | the reorganised ZFL text, or `null` |
| `zenwave/eventFlowViews` | `{ textDocument: { uri } }` | `{ flowGraph, serviceGraph }`: dsl-kotlin's laid-out flow and service view models, each with its `schema` (`zfl.eventflow.view@1`, `zfl.services.view@1`) |
| `zenwave/preview` | `{ textDocument: { uri }, mode?: "docs" \| "diagrams", sequenceRenderMode?: "SEPARATE_VARIANTS" \| "ALT_BLOCKS" \| "AUTO" }` | `{ representations: { id, title, format: "MARKDOWN" \| "MERMAID" \| "HTML", content, group? }[], defaultRepresentationId }` |
| `zenwave/symbolAt` | `{ textDocument: { uri }, position: { line, character } }` | `{ uri, semanticId, range? }`, the input of the two reference requests, or `null` when nothing is declared or referenced there |

`zenwave/hierarchy` answers whether or not the document is open. An open document is answered from the
editor's content; any other document is read by the server: files through Node's `fs` (the Node entry point),
`http(s):` through `fetch` (both entry points). A document it cannot read fails with `-32803`
`documentNotFound`; the worker cannot read `file:` documents, nor schemes such as `vscode-vfs:`. A document no
module builds hierarchies for answers `[]`. In a ZFL hierarchy the systems, services and commands that the
annotated ZDL declares point at that ZDL (`sourceUri`/`sourceRange`), open or read, and list where the flow
refers to them as a `referenced-by` related resource. `viewNodeIds` names the ids of the nodes and service
groups of the same document's `zenwave/eventFlowViews` that stand for the node's concept (`command:createOrder`,
`event:OrderCreated`, `event:OrderCreated@Orders>OrderService`, `group:Orders>OrderService`, `policy:…`); it
is empty when the diagram has no counterpart.

`zenwave/organizeZflServices` defaults to `mode: "full"`, which rebuilds the systems block from the flows.
`mode: "additive"` adds what the flows use and removes nothing. A document the server cannot process answers
`null` in either mode.

`zenwave/preview` defaults to `mode: "docs"`, so it answers for ZDL (one `MARKDOWN` representation `domain`, "Domain model": the model's page with its
diagrams as `mermaid` fences), `.zw` (one `MARKDOWN` representation `configuration`, "Plugin configuration": the `config`
block and each plugin with what it is for and its resolved configuration; the document's language id is `zw`, or its
URI ends in `.zw`) and ZFL (a `flowchart`, then one `sequence:<outcome>:<index>` per end outcome; the first sequence is
the default). What a model wrote is escaped in the Markdown, so a `<script>` shows as text. `sequenceRenderMode`
defaults to `ALT_BLOCKS`. Null properties are omitted from every result.

For a ZDL, `mode: "diagrams"` returns only individual `MERMAID` diagrams. Every representation has a `group` of
`Services`, `Aggregates`, `State Machines`, `Events`, `Commands` or `Unused entities and enums`; absent kinds have no representation.

### Plugin catalogue

The `.zw` page describes each plugin (what it is for, what its options mean) from a plugin catalogue, the JSON
the ZenWave SDK reports for `-h json`, in the shape of the VS Code extension's `sdk-plugin-catalogue.json`
(`{ source, version, plugins: [...] }`; fields the server does not know are ignored). The server ships its own copy,
embedded in the bundle, so it works with no file read (the Web Worker too), and uses the last valid catalogue a
client sent. The copy is generated by `scripts/generate-plugin-catalogue.mjs` from a named SDK build (its
`source`/`version`, today `ZenWave SDK 2.8.0-SNAPSHOT`) and parsed the first time a `.zw` is previewed.

- At initialisation: `initializationOptions.pluginCatalogue`, the catalogue as a JSON object.
- At any time: the notification `zenwave/pluginCatalogue` with params `{ catalogue: <JSON object> }`. It is advertised
  in `capabilities.experimental.customNotifications` (`["zenwave/pluginCatalogue"]`), beside `customRequests`.

The next `zenwave/preview` uses it (previews are not cached). A plugin that is not in the catalogue still shows its
name and resolved configuration, without a description. A catalogue that is not an object, is not JSON or has no
`plugins` array is ignored, the previous one stays, and the server logs a warning with `window/logMessage`;
it never fails initialisation.

Both visualisation requests read open documents. When a document cannot answer they fail with JSON-RPC
`-32803` and `data.kind`: `documentUnreadable` (a syntax error; `data.diagnostics` holds LSP diagnostics),
`documentNotFound` (not open) or `unsupportedDocument` (a kind of document the request does not cover).
Malformed params fail with `-32602`. An empty model is a successful, empty result.

The worker bundle carries elkjs for the flow layout. elkjs' in-process layout worker script would take over
the Web Worker's `onmessage` when it detects a worker global; `build.mjs` disables that detection when bundling,
and the build fails if an elkjs upgrade changes it.

Initialization options take the form `{ zenwave: { configUri?, projectManifestUri? }, pluginCatalogue? }`.

## Building

This package is built by the zenwave-lsp Gradle build (`./gradlew :lsp-js:lspJsBundle`, or
`:lsp-js:lspJsNpmPack` for a tarball in `build/npm`). `:lsp-js:check` runs both wire tests:
`nodeIpcTest` forks the Node entry point, and `jsBrowserTest` loads the worker in headless Chromium.

See [npm publishing](../docs/npm-publishing.md) for snapshots and trusted publisher setup.

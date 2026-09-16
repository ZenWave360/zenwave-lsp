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
  with JSON-RPC `-32601`. lsp-jvm advertises the same list.

## Custom requests

| Method | Params | Result |
| --- | --- | --- |
| `zenwave/hierarchy` | `{ uri }` | hierarchy nodes `{ id, label, kind, language, sourceUri, sourceRange, children, relatedResources, uiHints }[]` |
| `zenwave/forwardReferences`, `zenwave/reverseReferences` | `{ uri, semanticId }` | navigation targets |
| `zenwave/organizeZflServices` | `{ uri }` | the reorganised ZFL text, or `null` |
| `zenwave/eventFlowViews` | `{ textDocument: { uri } }` | `{ flowGraph, serviceGraph }`: dsl-kotlin's laid-out flow and service view models, each with its `schema` (`zfl.eventflow.view@1`, `zfl.services.view@1`) |
| `zenwave/preview` | `{ textDocument: { uri }, sequenceRenderMode?: "SEPARATE_VARIANTS" \| "ALT_BLOCKS" \| "AUTO" }` | `{ representations: { id, title, format: "MARKDOWN" \| "MERMAID" \| "HTML", content }[], defaultRepresentationId }` |

`zenwave/preview` answers for ZDL (one Mermaid `class-diagram`) and ZFL (a `flowchart`, then one
`sequence:<outcome>:<index>` per end outcome; the first sequence is the default). `sequenceRenderMode`
defaults to `ALT_BLOCKS`. Null properties are omitted from every result.

Both visualisation requests read open documents. When a document cannot answer they fail with JSON-RPC
`-32803` and `data.kind`: `documentUnreadable` (a syntax error; `data.diagnostics` holds LSP diagnostics),
`documentNotFound` (not open) or `unsupportedDocument` (a kind of document the request does not cover).
Malformed params fail with `-32602`. An empty model is a successful, empty result.

The worker bundle carries elkjs for the flow layout. elkjs' in-process layout worker script would take over
the Web Worker's `onmessage` when it detects a worker global; `build.mjs` disables that detection when bundling,
and the build fails if an elkjs upgrade changes it.

Initialization options take the form `{ zenwave: { configUri?, projectManifestUri? } }`.

## Building

This package is built by the zenwave-lsp Gradle build (`./gradlew :lsp-js:lspJsBundle`, or
`:lsp-js:lspJsNpmPack` for a tarball in `lsp-js/build/npm-pack`). `:lsp-js:check` runs both wire tests:
`nodeIpcTest` forks the Node entry point, and `jsBrowserTest` loads the worker in headless Chromium.

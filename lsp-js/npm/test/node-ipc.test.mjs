// Wire test for the Node entry point of @zenwave360/lsp-js: forks the packaged server as a child process
// and speaks LSP to it over child-process IPC, as vscode-languageclient's TransportKind.ipc does.
import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, before, describe, test } from 'node:test';
import { createMessageConnection, IPCMessageReader, IPCMessageWriter } from 'vscode-jsonrpc/node.js';

const packageDir = process.env.LSP_JS_PACKAGE_DIR;
assert.ok(packageDir, 'LSP_JS_PACKAGE_DIR names the assembled @zenwave360/lsp-js package');
const packageJson = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
const serverPath = join(packageDir, packageJson.exports['./node']);

const EXPECTED_CUSTOM_REQUESTS = [
  'zenwave/hierarchy',
  'zenwave/forwardReferences',
  'zenwave/reverseReferences',
  'zenwave/organizeZflServices',
];

const ordersUri = 'file:///workspace/orders.zdl';
const ordersText = [
  'entity Customer {',
  '    name String required',
  '}',
  '',
  'entity CustomerOrder {',
  '    customer Customer',
  '    total Integer',
  '}',
  '',
].join('\n');
const brokenUri = 'file:///workspace/broken.zdl';
const brokenText = [
  'entity Customer {',
  '    name String required',
  '}',
  '',
  'aggregate CustomerAggregate(MissingEntity) {',
  '}',
  '',
].join('\n');

function startServer(args) {
  const child = fork(serverPath, args, { stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  child.stdout.resume();
  const connection = createMessageConnection(new IPCMessageReader(child), new IPCMessageWriter(child));
  const diagnostics = new Map();
  const diagnosticWaiters = [];
  connection.onNotification('textDocument/publishDiagnostics', (params) => {
    diagnostics.set(params.uri, params.diagnostics);
    for (const waiter of diagnosticWaiters.splice(0)) waiter();
  });
  connection.onRequest(() => null);
  connection.listen();
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  return {
    child,
    connection,
    exited,
    stderr: () => stderr,
    async diagnosticsFor(uri) {
      while (!diagnostics.has(uri)) {
        await new Promise((resolve) => diagnosticWaiters.push(resolve));
      }
      const result = diagnostics.get(uri);
      diagnostics.delete(uri);
      return result;
    },
  };
}

function initializeParams() {
  return {
    processId: process.pid,
    rootUri: null,
    capabilities: {},
    initializationOptions: { zenwave: { projectManifestUri: 'file:///workspace/zenwave-architecture.yml' } },
  };
}

describe('Node IPC entry point', { timeout: 120_000 }, () => {
  let server;

  before(() => {
    server = startServer(['--node-ipc']);
  });

  after(() => {
    if (server.child.exitCode === null) server.child.kill();
  });

  test('initialize advertises capabilities and custom requests', async () => {
    const result = await server.connection.sendRequest('initialize', initializeParams());
    assert.equal(result.serverInfo.name, 'zenwave-lsp');
    assert.equal(result.serverInfo.version, packageJson.version);
    assert.equal(result.capabilities.textDocumentSync, 2);
    assert.equal(result.capabilities.hoverProvider, true);
    assert.equal(result.capabilities.definitionProvider, true);
    assert.equal(result.capabilities.documentSymbolProvider, true);
    assert.equal(result.capabilities.documentFormattingProvider, true);
    assert.deepEqual(result.capabilities.experimental.customRequests, EXPECTED_CUSTOM_REQUESTS);
    const languageIds = result.capabilities.experimental.moduleSelectors.map((selector) => selector.languageId);
    assert.ok(languageIds.includes('zdl') && languageIds.includes('zfl'), JSON.stringify(languageIds));
    await server.connection.sendNotification('initialized', {});
  });

  test('an opened ZDL document with a problem publishes diagnostics', async () => {
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: brokenUri, languageId: 'zdl', version: 1, text: brokenText },
    });
    const diagnostics = await server.diagnosticsFor(brokenUri);
    assert.ok(diagnostics.length > 0, 'diagnostics for an aggregate of an unknown entity');
    assert.ok(diagnostics[0].range.start.line >= 0);
    assert.ok([1, 2, 3, 4].includes(diagnostics[0].severity));
  });

  test('a valid ZDL document is served: diagnostics, hover, symbols and zenwave/hierarchy', async () => {
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: ordersUri, languageId: 'zdl', version: 1, text: ordersText },
    });
    assert.deepEqual(await server.diagnosticsFor(ordersUri), []);

    const hover = await server.connection.sendRequest('textDocument/hover', {
      textDocument: { uri: ordersUri },
      position: { line: 5, character: 15 },
    });
    assert.equal(hover?.contents?.kind, 'markdown');

    const symbols = await server.connection.sendRequest('textDocument/documentSymbol', { textDocument: { uri: ordersUri } });
    const names = JSON.stringify(symbols);
    assert.ok(names.includes('"CustomerOrder"'), names);

    const hierarchy = await server.connection.sendRequest('zenwave/hierarchy', { uri: ordersUri });
    assert.ok(Array.isArray(hierarchy) && hierarchy.length > 0);
    for (const key of ['id', 'label', 'kind', 'language', 'sourceUri', 'sourceRange', 'children', 'relatedResources', 'uiHints']) {
      assert.ok(key in hierarchy[0], `hierarchy node has ${key}`);
    }
  });

  test('incremental changes are applied', async () => {
    const line = brokenText.split('\n')[4];
    const start = line.indexOf('MissingEntity');
    await server.connection.sendNotification('textDocument/didChange', {
      textDocument: { uri: brokenUri, version: 2 },
      contentChanges: [{
        range: { start: { line: 4, character: start }, end: { line: 4, character: start + 'MissingEntity'.length } },
        text: 'Customer',
      }],
    });
    assert.deepEqual(await server.diagnosticsFor(brokenUri), []);
  });

  test('an unregistered request is answered with MethodNotFound', async () => {
    await assert.rejects(
      server.connection.sendRequest('zenwave/doesNotExist', { uri: ordersUri }),
      (error) => error.code === -32601,
    );
  });

  test('shutdown and exit end the process cleanly', async () => {
    await server.connection.sendRequest('shutdown');
    await server.connection.sendNotification('exit');
    assert.equal(await server.exited, 0, server.stderr());
  });
});

describe('Node entry point forked without transport arguments', { timeout: 120_000 }, () => {
  test('defaults to IPC', async () => {
    const server = startServer([]);
    try {
      const result = await server.connection.sendRequest('initialize', initializeParams());
      assert.deepEqual(result.capabilities.experimental.customRequests, EXPECTED_CUSTOM_REQUESTS);
      await server.connection.sendRequest('shutdown');
      await server.connection.sendNotification('exit');
      assert.equal(await server.exited, 0, server.stderr());
    } finally {
      if (server.child.exitCode === null) server.child.kill();
    }
  });
});

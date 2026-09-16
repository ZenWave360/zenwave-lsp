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
  'zenwave/eventFlowViews',
  'zenwave/preview',
];

const checkoutUri = 'file:///workspace/checkout.zfl';
const checkoutText = [
  'systems {',
  '    @zdl("orders/model.zdl")',
  '    Orders {',
  '        service OrderService {',
  '            commands: createOrder',
  '        }',
  '    }',
  '}',
  '',
  'flow CheckoutFlow {',
  '    @actor(Customer)',
  '    start CheckoutStarted {',
  '    }',
  '',
  '    when CheckoutStarted do createOrder {',
  '        service Orders.OrderService',
  '        emits OrderCreated',
  '        emits OrderRejected',
  '    }',
  '',
  '    end {',
  '        completed: OrderCreated',
  '        rejected: OrderRejected',
  '    }',
  '}',
  '',
].join('\n');
const brokenZflUri = 'file:///workspace/broken.zfl';
const brokenZflText = 'flow Broken {\n    when {{ do\n';
const REQUEST_FAILED = -32803;
const INVALID_PARAMS = -32602;

const HIERARCHY_NODE_KEYS = ['id', 'label', 'kind', 'language', 'sourceUri', 'sourceRange', 'children', 'relatedResources', 'uiHints'];

function assertHierarchyNode(node, path = node.label) {
  for (const key of HIERARCHY_NODE_KEYS) assert.ok(key in node, `${path} has ${key}: ${JSON.stringify(node)}`);
  assert.equal(typeof node.sourceRange.start.line, 'number', `${path} sourceRange`);
  assert.ok(Array.isArray(node.children) && Array.isArray(node.relatedResources), path);
  let depth = 1;
  for (const child of node.children) depth = Math.max(depth, 1 + assertHierarchyNode(child, `${path}/${child.label}`));
  return depth;
}

function requestFailure(server, method, params) {
  return server.connection.sendRequest(method, params).then(
    (result) => assert.fail(`${method} answered ${JSON.stringify(result)} instead of an error`),
    (error) => error,
  );
}

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

  test('zenwave/hierarchy nodes serialise recursively, and the reference requests answer over the wire', async () => {
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: checkoutUri, languageId: 'zfl', version: 1, text: checkoutText },
    });
    const hierarchy = await server.connection.sendRequest('zenwave/hierarchy', { uri: checkoutUri });
    assert.ok(Array.isArray(hierarchy) && hierarchy.length > 0);
    const depth = Math.max(...hierarchy.map((node) => assertHierarchyNode(node)));
    assert.ok(depth >= 4, `systems > system > service > command, got depth ${depth}`);

    const system = hierarchy.flatMap((node) => node.children).find((node) => node.kind === 'system');
    assert.ok(system, JSON.stringify(hierarchy));
    assert.equal(system.label, 'Orders');
    assert.equal(system.relatedResources[0].relationType, 'declares-domain');
    assert.ok(!('range' in system.relatedResources[0]), 'a null range is omitted');

    const forward = await server.connection.sendRequest('zenwave/forwardReferences', { uri: checkoutUri, semanticId: system.id });
    assert.ok(Array.isArray(forward) && forward.length > 0, JSON.stringify(forward));
    assert.equal(forward[0].relationType, 'declares-domain');
    assert.equal(typeof forward[0].uri, 'string');
    const reverse = await server.connection.sendRequest('zenwave/reverseReferences', { uri: checkoutUri, semanticId: system.id });
    assert.ok(Array.isArray(reverse));

    const organized = await server.connection.sendRequest('zenwave/organizeZflServices', { uri: checkoutUri });
    assert.ok(organized === null || typeof organized === 'string');
  });

  test('zenwave/eventFlowViews returns the laid-out flow and service view models', async () => {
    const views = await server.connection.sendRequest('zenwave/eventFlowViews', { textDocument: { uri: checkoutUri } });
    assert.deepEqual(Object.keys(views).sort(), ['flowGraph', 'serviceGraph']);
    assert.equal(views.flowGraph.schema, 'zfl.eventflow.view@1');
    assert.equal(views.serviceGraph.schema, 'zfl.services.view@1');
    assert.ok(views.flowGraph.nodes.length > 0);
    for (const node of views.flowGraph.nodes) {
      assert.equal(typeof node.position?.x, 'number', JSON.stringify(node));
      assert.equal(typeof node.dimensions?.width, 'number', JSON.stringify(node));
      assert.equal(typeof node.sourceRef?.line, 'number', JSON.stringify(node));
    }
    assert.ok(views.flowGraph.edges.length > 0);
    assert.equal(typeof views.flowGraph.layout.engine, 'string');
    assert.ok(views.serviceGraph.nodes.every((node) => typeof node.position?.y === 'number'));
    assert.ok(!JSON.stringify(views).includes('null'), 'null properties are omitted');
  });

  test('zenwave/preview returns ordered representations for ZFL and ZDL', async () => {
    const zfl = await server.connection.sendRequest('zenwave/preview', { textDocument: { uri: checkoutUri } });
    assert.deepEqual(zfl.representations.map((r) => r.id), ['flowchart', 'sequence:completed:0', 'sequence:rejected:1']);
    assert.equal(zfl.defaultRepresentationId, 'sequence:completed:0');
    for (const representation of zfl.representations) {
      assert.deepEqual(Object.keys(representation).sort(), ['content', 'format', 'id', 'title']);
      assert.equal(representation.format, 'MERMAID');
    }
    assert.equal(zfl.representations[0].title, 'Flowchart');
    assert.ok(zfl.representations[0].content.startsWith('flowchart'));

    const separate = await server.connection.sendRequest('zenwave/preview', {
      textDocument: { uri: checkoutUri },
      sequenceRenderMode: 'SEPARATE_VARIANTS',
    });
    assert.equal(separate.representations[0].id, 'flowchart');

    const zdl = await server.connection.sendRequest('zenwave/preview', { textDocument: { uri: ordersUri } });
    assert.deepEqual(zdl.representations.map(({ id, title, format }) => ({ id, title, format })), [
      { id: 'class-diagram', title: 'Class diagram', format: 'MERMAID' },
    ]);
    assert.equal(zdl.defaultRepresentationId, 'class-diagram');
    assert.ok(zdl.representations[0].content.startsWith('classDiagram'));
  });

  test('documents that cannot answer are errors with a kind, never empty results', async () => {
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: brokenZflUri, languageId: 'zfl', version: 1, text: brokenZflText },
    });
    for (const method of ['zenwave/preview', 'zenwave/eventFlowViews']) {
      const unreadable = await requestFailure(server, method, { textDocument: { uri: brokenZflUri } });
      assert.equal(unreadable.code, REQUEST_FAILED);
      assert.equal(unreadable.data.kind, 'documentUnreadable');
      assert.ok(unreadable.data.diagnostics.length > 0);
      assert.equal(typeof unreadable.data.diagnostics[0].range.start.line, 'number');
      assert.equal(unreadable.data.diagnostics[0].severity, 1);

      const notFound = await requestFailure(server, method, { textDocument: { uri: 'file:///workspace/never-opened.zfl' } });
      assert.equal(notFound.code, REQUEST_FAILED);
      assert.deepEqual(notFound.data, { kind: 'documentNotFound' });
    }
    const unsupported = await requestFailure(server, 'zenwave/eventFlowViews', { textDocument: { uri: ordersUri } });
    assert.equal(unsupported.code, REQUEST_FAILED);
    assert.deepEqual(unsupported.data, { kind: 'unsupportedDocument' });

    const badMode = await requestFailure(server, 'zenwave/preview', { textDocument: { uri: checkoutUri }, sequenceRenderMode: 'SIDEWAYS' });
    assert.equal(badMode.code, INVALID_PARAMS);
    const noDocument = await requestFailure(server, 'zenwave/preview', {});
    assert.equal(noDocument.code, INVALID_PARAMS);
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

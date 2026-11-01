// Wire test for the Node entry point of @zenwave360/lsp-js: forks the packaged server as a child process
// and speaks LSP to it over child-process IPC, as vscode-languageclient's TransportKind.ipc does.
import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { after, afterEach, before, describe, test } from 'node:test';
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
  'zenwave/symbolAt',
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

const HIERARCHY_NODE_KEYS = ['id', 'label', 'kind', 'language', 'sourceUri', 'sourceRange', 'children', 'relatedResources', 'uiHints', 'viewNodeIds'];

function flatten(node) {
  return [node, ...node.children.flatMap(flatten)];
}

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
  const logs = [];
  connection.onNotification('window/logMessage', (params) => logs.push(params));
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
    logs,
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
    assert.deepEqual(result.capabilities.experimental.customNotifications, ['zenwave/pluginCatalogue']);
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
      { id: 'domain', title: 'Domain model', format: 'MARKDOWN' },
    ]);
    assert.equal(zdl.defaultRepresentationId, 'domain');
    assert.ok(zdl.representations[0].content.includes('Customer'));

    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: 'file:///workspace/orders.zw', languageId: 'zw', version: 1, text: ordersText },
    });
    const zw = await server.connection.sendRequest('zenwave/preview', { textDocument: { uri: 'file:///workspace/orders.zw' } });
    assert.deepEqual(zw.representations.map(({ id, format }) => ({ id, format })), [{ id: 'configuration', format: 'MARKDOWN' }]);
    assert.equal(zw.defaultRepresentationId, 'configuration');
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: 'file:///workspace/broken.zw', languageId: 'zw', version: 1, text: 'entity Customer {\n  name String required\n  ) ) )\n}\n' },
    });
    const brokenZw = await requestFailure(server, 'zenwave/preview', { textDocument: { uri: 'file:///workspace/broken.zw' } });
    assert.equal(brokenZw.code, REQUEST_FAILED);
    assert.equal(brokenZw.data.kind, 'documentUnreadable');
  });

  test('zenwave/preview never serves model-controlled annotation text as an image or script', async () => {
    const zdlWith = (api) => [
      'entity Order { name String }',
      'aggregate OrderAggregate(Order) {}',
      'service Orders for (OrderAggregate) {',
      `    @asyncapi(api: "${api}", channel: "c")`,
      '    create(Order) Order',
      '}',
      '',
    ].join('\n');
    const previewOf = async (uri, api) => {
      await server.connection.sendNotification('textDocument/didOpen', {
        textDocument: { uri, languageId: 'zdl', version: 1, text: zdlWith(api) },
      });
      const preview = await server.connection.sendRequest('zenwave/preview', { textDocument: { uri } });
      const [representation] = preview.representations;
      assert.equal(representation.format, 'MARKDOWN');
      return representation.content;
    };
    const image = await previewOf('file:///workspace/hostile-image.zdl', '![x](https://example.invalid/preview-probe.png)');
    assert.ok(!image.includes('![x](https://example.invalid'), image);
    const script = await previewOf('file:///workspace/hostile-script.zdl', '<script>alert(1)</script>');
    assert.ok(!script.includes('<script'), script);
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

  test('zenwave/hierarchy reads documents that are not open, and nodes point at the declaring ZDL', async () => {
    const workspace = mkdtempSync(join(tmpdir(), 'zenwave-ipc-'));
    mkdirSync(join(workspace, 'orders'));
    const zdlPath = join(workspace, 'orders', 'model.zdl');
    writeFileSync(zdlPath, ['entity Order {', '    total Integer', '}', '', 'service OrderService for (Order) {', '    createOrder(Order) Order', '}', ''].join('\n'));
    const zflPath = join(workspace, 'checkout.zfl');
    writeFileSync(zflPath, checkoutText);
    const zdlUri = pathToFileURL(zdlPath).href;
    const zflUri = pathToFileURL(zflPath).href;

    const hierarchy = await server.connection.sendRequest('zenwave/hierarchy', { uri: zflUri });
    hierarchy.forEach((node) => assertHierarchyNode(node));
    const nodes = hierarchy.flatMap(flatten);
    assert.equal(nodes.find((node) => node.kind === 'system').sourceUri, zdlUri);
    const command = nodes.find((node) => node.kind === 'command');
    assert.equal(command.sourceUri, zdlUri, JSON.stringify(command));
    assert.deepEqual(command.viewNodeIds, ['command:createOrder']);
    assert.ok(command.relatedResources.some((target) => target.relationType === 'referenced-by' && target.uri === zflUri));

    const missing = await requestFailure(server, 'zenwave/hierarchy', { uri: pathToFileURL(join(workspace, 'missing.zdl')).href });
    assert.equal(missing.code, REQUEST_FAILED);
    assert.deepEqual(missing.data, { kind: 'documentNotFound' });
    assert.deepEqual(await server.connection.sendRequest('zenwave/hierarchy', { uri: pathToFileURL(join(workspace, 'notes.txt')).href }), []);
    assert.equal((await requestFailure(server, 'zenwave/hierarchy', {})).code, INVALID_PARAMS);
  });

  test('zenwave/symbolAt resolves a position into what the reference requests take', async () => {
    const lines = checkoutText.split('\n');
    const at = (line, character, uri = checkoutUri) => ({ textDocument: { uri }, position: { line, character } });
    const system = await server.connection.sendRequest('zenwave/symbolAt', at(lines.indexOf('    Orders {'), 6));
    assert.deepEqual(Object.keys(system).sort(), ['range', 'semanticId', 'uri']);
    assert.equal(system.semanticId, `${checkoutUri}#systems.Orders`);
    const forward = await server.connection.sendRequest('zenwave/forwardReferences', { uri: system.uri, semanticId: system.semanticId });
    assert.equal(forward[0].relationType, 'declares-domain');

    const whenLine = lines.findIndex((line) => line.includes('do createOrder'));
    const command = await server.connection.sendRequest('zenwave/symbolAt', at(whenLine, lines[whenLine].indexOf('createOrder') + 2));
    assert.equal(command.semanticId, `${checkoutUri}#flows.CheckoutFlow.commands.createOrder`);
    const uses = await server.connection.sendRequest('zenwave/forwardReferences', { uri: checkoutUri, semanticId: command.semanticId });
    assert.equal(uses[0].relationType, 'uses');

    assert.equal(await server.connection.sendRequest('zenwave/symbolAt', at(lines.indexOf(''), 0)), null);
    const notOpen = await requestFailure(server, 'zenwave/symbolAt', at(0, 0, 'file:///workspace/never-opened.zfl'));
    assert.deepEqual(notOpen.data, { kind: 'documentNotFound' });
    assert.equal((await requestFailure(server, 'zenwave/symbolAt', { textDocument: { uri: checkoutUri } })).code, INVALID_PARAMS);
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

describe('Plugin catalogue (MP-R16) over Node IPC', { timeout: 120_000 }, () => {
  const scriptsUri = 'file:///workspace/scripts.zw';
  const scriptsText = [
    'config {',
    '    title "Orders scripts"',
    '    basePackage "io.example.orders"',
    '    plugins {',
    '        ZDLToOpenAPIPlugin {',
    '            targetFile "./openapi.yml"',
    '        }',
    '        TeamOwnPlugin {',
    '            maxDepth 3',
    '        }',
    '    }',
    '}',
    '',
  ].join('\n');
  const shippedSummary = 'Generates a draft OpenAPI definitions from your ZDL entities and services.';
  const catalogue = (summary, extra = {}) => ({
    source: 'Test SDK',
    version: '9.9',
    ...extra,
    plugins: summary === null ? [] : [{ name: 'ZDLToOpenAPIPlugin', configClassName: 'x.ZDLToOpenAPIPlugin', summary }],
  });
  let server;

  async function start(initializationOptions) {
    server = startServer(['--node-ipc']);
    await server.connection.sendRequest('initialize', { processId: process.pid, rootUri: null, capabilities: {}, initializationOptions });
    await server.connection.sendNotification('initialized', {});
    await server.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: scriptsUri, languageId: 'zw', version: 1, text: scriptsText },
    });
  }

  async function page() {
    const preview = await server.connection.sendRequest('zenwave/preview', { textDocument: { uri: scriptsUri } });
    assert.equal(preview.defaultRepresentationId, 'configuration');
    assert.deepEqual(preview.representations.map(({ id, title, format }) => ({ id, title, format })), [
      { id: 'configuration', title: 'Plugin configuration', format: 'MARKDOWN' },
    ]);
    return preview.representations[0].content;
  }

  afterEach(() => {
    if (server?.child.exitCode === null) server.child.kill();
  });

  test('a .zw page is described by the server\'s own copy of the catalogue', async () => {
    await start(undefined);
    const content = await page();
    assert.ok(content.includes('### ZDLToOpenAPIPlugin'), content);
    assert.ok(content.includes(shippedSummary), content);
    assert.ok(content.includes('### TeamOwnPlugin'), content);
  });

  test('a catalogue in initializationOptions replaces it, tolerating unknown fields', async () => {
    await start({ pluginCatalogue: catalogue('Summary from the client', { somethingNew: { a: [1] } }) });
    const content = await page();
    assert.ok(content.includes('Summary from the client'), content);
    assert.ok(!content.includes(shippedSummary), content);
  });

  test('a notification replaces it for the next preview; a malformed one keeps the previous and is logged', async () => {
    await start({});
    assert.ok((await page()).includes(shippedSummary));

    await server.connection.sendNotification('zenwave/pluginCatalogue', { catalogue: catalogue('First replacement') });
    assert.ok((await page()).includes('First replacement'));
    await server.connection.sendNotification('zenwave/pluginCatalogue', { catalogue: catalogue('Second replacement') });
    const second = await page();
    assert.ok(second.includes('Second replacement') && !second.includes('First replacement'), second);

    await server.connection.sendNotification('zenwave/pluginCatalogue', { catalogue: { plugins: 'nope' } });
    await server.connection.sendNotification('zenwave/pluginCatalogue', { catalogue: 'text' });
    await server.connection.sendNotification('zenwave/pluginCatalogue', {});
    assert.ok((await page()).includes('Second replacement'));
    assert.ok(server.logs.length >= 1 && server.logs.every((log) => log.type === 2 && log.message.includes('plugin catalogue')), JSON.stringify(server.logs));
  });

  test('a plugin missing from the replacement shows without a description', async () => {
    await start({ pluginCatalogue: catalogue(null) });
    const content = await page();
    assert.ok(content.includes('### ZDLToOpenAPIPlugin') && content.includes('targetFile'), content);
    assert.ok(!content.includes(shippedSummary), content);
  });

  test('a malformed catalogue in initializationOptions never fails initialisation', async () => {
    await start({ pluginCatalogue: 'not a catalogue' });
    assert.ok((await page()).includes(shippedSummary));
    assert.ok(server.logs.some((log) => log.message.includes('plugin catalogue')));
  });
});

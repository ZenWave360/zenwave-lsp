// Bundles the lsp-js entry points and assembles the @zenwave360/lsp-js npm package.
// Invoked by the Gradle task :lsp-js:lspJsBundle:
//   node build.mjs --kotlin-dir <Kotlin/JS production ESM dir> --out <package dir> --version <version> --readme <README.md>
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url));
const { values } = parseArgs({
  options: {
    'kotlin-dir': { type: 'string' },
    out: { type: 'string' },
    version: { type: 'string' },
    readme: { type: 'string' },
    license: { type: 'string' },
  },
});
for (const required of ['kotlin-dir', 'out', 'version', 'readme', 'license']) {
  if (!values[required]) throw new Error(`--${required} is required`);
}

const kotlinModule = resolve(values['kotlin-dir'], 'zenwave-lsp-lsp-js.mjs');
const outDir = resolve(values.out);

// Resolves the Kotlin/JS server module, which the entry points import as 'zenwave-lsp-kotlin'.
const kotlinModulePlugin = {
  name: 'zenwave-lsp-kotlin',
  setup(pluginBuild) {
    pluginBuild.onResolve({ filter: /^zenwave-lsp-kotlin$/ }, () => ({ path: kotlinModule }));
  },
};

// elkjs (EventFlow layout) runs its layout in a "fake worker" loaded from elk-worker.min.js. That script
// assumes that a global `self` without `document` means it *is* ELK's dedicated worker, and takes over
// `self.onmessage` — inside our Web Worker that would steal the LSP connection's message handler and leave
// ELK without its in-process worker. The worker bundle therefore disables that branch, so the script takes its
// module branch, as it does on Node. The build fails if the expected code is not found (an elkjs upgrade).
const elkWorkerSelfDetection = 'typeof document===Yxe&&typeof self!==Yxe';
const elkjsInProcessWorkerPlugin = {
  name: 'elkjs-in-process-worker',
  setup(pluginBuild) {
    pluginBuild.onLoad({ filter: /elkjs[\\/]lib[\\/]elk-worker\.min\.js$/ }, (args) => {
      const source = readFileSync(args.path, 'utf8');
      const occurrences = source.split(elkWorkerSelfDetection).length - 1;
      if (occurrences !== 1) {
        throw new Error(`${args.path}: expected the worker self-detection exactly once, found ${occurrences}`);
      }
      return { contents: source.replace(elkWorkerSelfDetection, 'false'), loader: 'js' };
    });
  },
};

const common = {
  bundle: true,
  logLevel: 'warning',
  legalComments: 'none',
  plugins: [kotlinModulePlugin],
  // The Kotlin output imports elkjs; it resolves from this npm project, which pins the version lsp-core declares.
  nodePaths: [join(here, 'node_modules')],
  // elkjs probes for the optional 'web-worker' package inside a try/catch and only uses it when given a workerUrl.
  external: ['web-worker'],
  banner: { js: `/* @zenwave360/lsp-js ${values.version} */` },
};

rmSync(outDir, { recursive: true, force: true });

await build({
  ...common,
  entryPoints: [join(here, 'src/node-server.js')],
  outfile: join(outDir, 'dist/node/zenwave-lsp-server.js'),
  platform: 'node',
  format: 'cjs',
  target: 'node18',
});

// platform 'browser' makes any Node built-in reaching the worker a build error (the browser check).
await build({
  ...common,
  entryPoints: [join(here, 'src/worker.js')],
  outfile: join(outDir, 'dist/browser/zenwave-lsp-worker.js'),
  plugins: [kotlinModulePlugin, elkjsInProcessWorkerPlugin],
  platform: 'browser',
  format: 'iife',
  target: 'es2020',
  mainFields: ['browser', 'module', 'main'],
  conditions: ['browser'],
});

const packageJson = {
  name: '@zenwave360/lsp-js',
  version: values.version,
  description: 'ZenWave language server (ZDL, ZFL, architecture manifest, AsyncAPI, OpenAPI, Avro) for Node and Web Workers',
  keywords: ['zenwave', 'zdl', 'zfl', 'language-server', 'lsp', 'domain-driven-design', 'event-storming'],
  homepage: 'https://github.com/ZenWave360/zenwave-lsp',
  repository: { type: 'git', url: 'https://github.com/ZenWave360/zenwave-lsp' },
  license: 'MIT',
  main: './dist/node/zenwave-lsp-server.js',
  browser: './dist/browser/zenwave-lsp-worker.js',
  exports: {
    './node': './dist/node/zenwave-lsp-server.js',
    './worker': './dist/browser/zenwave-lsp-worker.js',
    './package.json': './package.json',
  },
  files: ['dist', 'README.md', 'LICENSE'],
  publishConfig: {
    access: 'public',
    registry: 'https://registry.npmjs.org/',
    tag: values.version.includes('-') ? 'next' : 'latest',
  },
  engines: { node: '>=18' },
  zenwave: {
    lsp: {
      transports: {
        node: { entry: './dist/node/zenwave-lsp-server.js', transport: 'ipc' },
        worker: { entry: './dist/browser/zenwave-lsp-worker.js', transport: 'postMessage' },
      },
    },
  },
};
mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, 'package.json'), `${JSON.stringify(packageJson, null, 2)}\n`);
copyFileSync(resolve(values.readme), join(outDir, 'README.md'));
copyFileSync(resolve(values.license), join(outDir, 'LICENSE'));
console.log(`Assembled @zenwave360/lsp-js ${values.version} in ${outDir}`);

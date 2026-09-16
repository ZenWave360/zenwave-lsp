// Bundles the lsp-js entry points and assembles the @zenwave360/lsp-js npm package.
// Invoked by the Gradle task :lsp-js:lspJsBundle:
//   node build.mjs --kotlin-dir <Kotlin/JS production ESM dir> --out <package dir> --version <version> --readme <README.md>
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
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
  },
});
for (const required of ['kotlin-dir', 'out', 'version', 'readme']) {
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

const common = {
  bundle: true,
  logLevel: 'warning',
  legalComments: 'none',
  plugins: [kotlinModulePlugin],
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
  files: ['dist', 'README.md'],
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
console.log(`Assembled @zenwave360/lsp-js ${values.version} in ${outDir}`);

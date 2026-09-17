import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const registry = 'https://registry.npmjs.org/';
const packageName = '@zenwave360/lsp-js';
const semver = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$/;

export function npmVersion(source, { version = '', snapshotBuild = '' } = {}) {
  const projectVersion = source.match(/^\s*version = "([^"]+)"\r?$/m)?.[1];
  assert(projectVersion, 'Expected a root Gradle version');
  if (snapshotBuild) {
    assert(/^\d+\.\d+$/.test(snapshotBuild), 'Expected run_number.run_attempt');
    assert(/^\d+\.\d+\.\d+-SNAPSHOT$/.test(projectVersion), 'Snapshots require a Gradle SNAPSHOT version');
    return `${projectVersion.replace('-SNAPSHOT', '')}-next.${snapshotBuild}`;
  }
  const result = version || projectVersion.replace('-SNAPSHOT', '-next.0');
  assert(semver.test(result), `Invalid npm version: ${result}`);
  if (projectVersion.endsWith('-SNAPSHOT')) {
    assert(result.startsWith(`${projectVersion.replace('-SNAPSHOT', '')}-next.`), 'Bootstrap version must belong to the Gradle snapshot train');
  } else {
    assert.equal(result, projectVersion, 'npm and Gradle release versions must match');
  }
  return result;
}

export function releaseVersion(source, version) {
  assert(/^\d+\.\d+\.\d+(?:-rc\.\d+)?$/.test(version), `Invalid release version: ${version}`);
  assert.equal(npmVersion(source), version, 'Release tags require the same non-SNAPSHOT Gradle version');
  return version;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, `${command} failed: ${result.stderr || ''}`);
  return result.stdout;
}

export function verifyTarball(version, directory = 'build/npm') {
  assert(semver.test(version), `Invalid npm version: ${version}`);
  const path = resolve(directory, `zenwave360-lsp-js-${version}.tgz`);
  const entries = run('tar', ['-tzf', path]).trim().split(/\r?\n/);
  assert(entries.every(entry => entry.startsWith('package/') && !entry.split('/').includes('..')), 'Invalid tarball entries');
  const pkg = JSON.parse(run('tar', ['-xzOf', path, 'package/package.json']));
  assert.equal(pkg.name, packageName);
  assert.equal(pkg.version, version);
  assert.equal(pkg.license, 'MIT');
  assert.equal(pkg.publishConfig?.access, 'public');
  assert.equal(pkg.publishConfig?.registry, registry);
  assert.equal(pkg.publishConfig?.tag, version.includes('-') ? 'next' : 'latest');
  assert.equal(Object.keys(pkg.scripts ?? {}).length, 0, 'Lifecycle scripts are not allowed');
  for (const entry of [pkg.exports?.['./node'], pkg.exports?.['./worker']]) {
    assert(entry && entries.includes(`package/${entry.replace(/^\.\//, '')}`), 'Missing Node or worker entry point');
  }
  assert(entries.includes('package/README.md') && entries.includes('package/LICENSE'), 'Missing documentation');
  for (const dependency of Object.values(pkg.dependencies ?? {})) {
    assert(!/^(?:file:|link:|workspace:)/.test(dependency), 'Local dependency in published package');
  }
  const integrity = `sha512-${createHash('sha512').update(readFileSync(path)).digest('base64')}`;
  return { path, name: packageName, version, integrity };
}

async function publish(version) {
  const artifact = verifyTarball(version);
  const response = await fetch(`${registry}${encodeURIComponent(packageName)}`);
  assert(response.ok, `Registry HTTP ${response.status}. The first publication must be manual.`);
  const existing = (await response.json()).versions?.[version];
  if (existing) {
    assert.equal(existing.dist?.integrity, artifact.integrity, `${packageName}@${version} already exists with different contents`);
    console.log(`Already published: ${packageName}@${version}`);
  } else {
    run('npm', ['publish', artifact.path, '--registry', registry, '--ignore-scripts', '--access', 'public', '--tag', version.includes('-') ? 'next' : 'latest', '--provenance'], { stdio: 'inherit' });
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `- ${packageName}@${version}\n`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, version] = process.argv.slice(2);
  if (command === 'version') {
    console.log(npmVersion(readFileSync('build.gradle.kts', 'utf8'), {
      version: process.env.NPM_VERSION,
      snapshotBuild: process.env.SNAPSHOT_BUILD,
    }));
  } else if (command === 'verify') {
    console.log(verifyTarball(version));
  } else if (command === 'publish') {
    await publish(version);
  } else if (command === 'release-version') {
    console.log(releaseVersion(readFileSync('build.gradle.kts', 'utf8'), version));
  } else {
    throw new Error('Usage: node scripts/npm-package.mjs version|verify VERSION|publish VERSION|release-version VERSION');
  }
}

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkRelease, npmVersion, releaseVersion } from './npm-package.mjs';

test('maps the shared Gradle version to bootstrap, immutable snapshots, and releases', () => {
  const source = 'version = "0.1.0-SNAPSHOT"\r\n';
  assert.equal(npmVersion(source), '0.1.0-next.0');
  assert.equal(npmVersion(source, { snapshotBuild: '18.2' }), '0.1.0-next.18.2');
  assert.equal(npmVersion('version = "0.1.0"\n'), '0.1.0');
  assert.equal(npmVersion('version = "0.1.0-rc.1"\n'), '0.1.0-rc.1');
});

test('rejects release drift and malformed snapshot inputs before building', () => {
  assert.throws(() => npmVersion('version = "0.1.0"', { snapshotBuild: '18.1' }));
  assert.throws(() => npmVersion('version = "0.1.0"', { version: '0.2.0' }));
  assert.throws(() => npmVersion('version = "0.1.0-SNAPSHOT"', { version: '0.2.0-next.0' }));
  assert.throws(() => npmVersion('version = "0.1.0-SNAPSHOT"', { snapshotBuild: '18' }));
  assert.throws(() => npmVersion(''));
});

test('release tags accept matching stable and release-candidate Gradle versions', () => {
  assert.equal(releaseVersion('version = "0.1.0"\r\n', '0.1.0'), '0.1.0');
  assert.equal(releaseVersion('version = "0.1.0-rc.1"\n', '0.1.0-rc.1'), '0.1.0-rc.1');
});

test('release tags reject snapshot builds, version drift, and unsupported tags', () => {
  assert.throws(() => releaseVersion('version = "0.1.0-SNAPSHOT"', '0.1.0'));
  assert.throws(() => releaseVersion('version = "0.1.0"', '0.2.0'));
  for (const version of ['0.1.0-next.1', '0.1.0-SNAPSHOT', 'v0.1.0', '0.1']) {
    assert.throws(() => releaseVersion('version = "0.1.0"', version));
  }
});

test('release preflight rejects duplicates, missing packages and registry failures before changing versions', async () => {
  const response = versions => async () => ({ ok: true, json: async () => ({ versions }) });
  await checkRelease('0.1.0', response({ '0.1.0-next.0': {} }));
  await checkRelease('0.1.0-rc.1', response({}));
  await assert.rejects(checkRelease('0.1.0', response({ '0.1.0': {} })), /already published/);
  for (const status of [404, 503]) {
    await assert.rejects(checkRelease('0.1.0', async () => ({ ok: false, status })), /registry HTTP/);
  }
  await assert.rejects(checkRelease('0.1.0-next.1', response({})), /Invalid release version/);
});

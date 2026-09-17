import assert from 'node:assert/strict';
import { test } from 'node:test';
import { npmVersion } from './npm-package.mjs';

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

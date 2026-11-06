import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fixture } from './fixture.ts';
test('web shell serves only the configured directory with restrictive response headers', async t => {
  const webRoot = await mkdtemp(join(tmpdir(), 'ams-web-')); t.after(() => rm(webRoot, { recursive: true, force: true }));
  await writeFile(join(webRoot, 'index.html'), '<!doctype html><title>应用管控</title>');
  const f = await fixture(t, { webRoot });
  const response = await f.app.inject('/'); assert.equal(response.statusCode, 200);
  assert.match(response.headers['content-security-policy'] ?? '', /default-src 'self'/);
  assert.equal(response.headers['x-content-type-options'], 'nosniff');
  assert.equal((await f.app.inject('/api/v1/admin/devices')).statusCode, 401);
  assert.equal((await f.app.inject('/manager.sqlite')).statusCode, 404);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../api/src/db.ts';
import { fixture } from './fixture.ts';
import { z } from 'zod';

test('missing table in an established schema fails instead of silently recreating empty data', async t => {
  const f = await fixture(t);
  const connection = new Store(f.directory);
  connection.db.exec('DROP TABLE usage_sessions'); connection.close();
  assert.throws(() => { new Store(f.directory).close(); }, /schema/i);
});

test('unrecognized stored TOTP identity version cannot authenticate', async t => {
  const f = await fixture(t);
  const connection = new Store(f.directory);
  const admin = connection.get('admins', 'administrator', z.record(z.string(), z.unknown()));
  connection.put('admins', 'administrator', '', { ...admin, version: 999 }); connection.close();
  await assert.rejects(f.admin(), /authentication rejected/);
});

test('malformed JSON and excessive request sizes preserve client error statuses', async t => {
  const f = await fixture(t);
  const response = await f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { 'content-type': 'application/json' }, payload: '{' });
  assert.equal(response.statusCode, 400);
  const large = await f.app.inject({ method: 'POST', url: '/api/v1/device/sync', payload: { value: 'x'.repeat(1024 * 1024 + 1) } });
  assert.equal(large.statusCode, 413);
});

test('v2 migration adds day-clear storage without changing existing usage data', async t => {
  const f = await fixture(t);const original = { id: 'kept-usage', marker: 'preserve existing payload' };
  const legacy = new Store(f.directory);legacy.put('usage_sessions', original.id, 'device', original);
  legacy.db.exec('DROP TABLE IF EXISTS usage_clearances; DROP TABLE IF EXISTS auth_attempts');legacy.db.pragma('user_version=2');legacy.close();
  const migrated = new Store(f.directory);
  try {
    assert.equal(migrated.db.pragma('user_version', { simple: true }), 4);
    assert.deepEqual(migrated.get('usage_sessions', original.id, z.object({ id: z.string(), marker: z.string() })), original);
    assert.deepEqual(migrated.db.prepare('SELECT count(*) n FROM usage_clearances').get(), { n: 0 });
  }finally { migrated.close(); }
});

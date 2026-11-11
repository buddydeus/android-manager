import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import Database from 'better-sqlite3';
import { Store } from '../api/src/db.ts';
import { backupData } from '../api/src/backup.ts';
import { fixture } from './fixture.ts';
test('backup includes a consistent SQLite snapshot and every referenced immutable APK', async t => {
  const f = await fixture(t); const device = await f.register();
  const destination = await mkdtemp(join(tmpdir(), 'ams-backup-test-'));t.after(() => rm(destination, { recursive: true, force: true }));
  const bytes = Buffer.from('fixture object');const sha256 = createHash('sha256').update(bytes).digest('hex');
  await mkdir(join(f.directory, 'releases'), { recursive: true });await writeFile(join(f.directory, 'releases', `${sha256}.apk`), bytes);
  const source = new Store(f.directory);source.put('releases', sha256, '', { id: sha256, sha256, size: bytes.length, packageName: 'com.buddydeus.androidmanager', versionCode: 2, versionName: '2', minSdk: 30, signers: ['a'.repeat(64)], createdAtMs: Date.now() });source.put('usage_clearances', `${device.deviceId}:2026-11-06`, device.deviceId, { date: '2026-11-06', dayStartedAtMs: 1793894400000, clearedAtMs: 1793937600000, sessionIds: [], gapIds: [] });source.close();
  const saved = await backupData(f.directory, destination);const snapshot = new Database(join(saved, 'manager.sqlite'), { readonly: true });
  try { assert.ok(snapshot.prepare('SELECT 1 FROM devices WHERE id=?').get(device.deviceId));assert.equal(snapshot.pragma('user_version', { simple: true }), 4);assert.ok(snapshot.prepare('SELECT 1 FROM usage_clearances WHERE deviceId=?').get(device.deviceId)); }finally { snapshot.close(); }
  assert.deepEqual(await readFile(join(saved, 'releases', `${sha256}.apk`)), bytes);
  const manifest = JSON.parse(await readFile(join(saved, 'backup.json'), 'utf8')) as { formatVersion: number };assert.equal(manifest.formatVersion, 1);
});

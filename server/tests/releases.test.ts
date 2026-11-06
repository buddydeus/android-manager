import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './fixture.ts';
function multipart(bytes: Buffer) { return Buffer.concat([Buffer.from('--ams\r\nContent-Disposition: form-data; name="apk"; filename="update.apk"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n'), bytes, Buffer.from('\r\n--ams--\r\n')]); }
test('APK upload requires administrator and invalid APK never creates a release', async t => {
  const f = await fixture(t); const body = multipart(Buffer.from('not an apk'));
  const headers = { ...await f.admin(), 'content-type': 'multipart/form-data; boundary=ams' };
  assert.equal((await f.app.inject({ method: 'POST', url: '/api/v1/admin/releases', headers: { 'content-type': headers['content-type'] }, payload: body })).statusCode, 401);
  const result = await f.app.inject({ method: 'POST', url: '/api/v1/admin/releases', headers, payload: body }); assert.equal(result.statusCode, 400);
  assert.equal((await f.app.inject({ url: '/api/v1/admin/releases', headers })).json().data.length, 0);
});
test('upgrade queue allows offline targets and does not accept arbitrary download URLs', async t => {
  const f = await fixture(t); const d = await f.register(); const admin = await f.admin();
  const result = await f.app.inject({ method: 'POST', url: `/api/v1/admin/devices/${d.deviceId}/upgrades`, headers: admin, payload: { jobId: randomUUID(), releaseId: 'a'.repeat(64), url: 'https://other.invalid/app.apk' } });
  assert.equal(result.statusCode, 400);
});
test('queued upgrades are scoped, idempotent and success needs the installed target version', async t => {
  const { Store } = await import('../api/src/db.ts');
  const f = await fixture(t); const d = await f.register(); const another = await f.register(); const admin = await f.admin();
  const releaseId = 'b'.repeat(64); const store = new Store(f.directory);
  try { store.put('releases', releaseId, '', { id: releaseId, sha256: releaseId, size: 100, packageName: 'com.buddydeus.androidmanager', versionCode: 2, versionName: '2.0', minSdk: 30, signers: ['c'.repeat(64)], createdAtMs: Date.now() }); } finally { store.close(); }
  const jobId = randomUUID(); const input = { jobId, releaseId };
  const issue = () => f.app.inject({ method: 'POST', url: `/api/v1/admin/devices/${d.deviceId}/upgrades`, headers: admin, payload: input });
  assert.equal((await issue()).statusCode, 200); assert.equal((await issue()).json().data.id, jobId);
  const headers = { authorization: `Bearer ${d.token}` };
  const jobs = (await f.app.inject({ url: '/api/v1/device/upgrades', headers })).json().data.jobs;
  assert.equal(jobs.length, 1); assert.equal(jobs[0].release.versionCode, 2);
  assert.equal((await f.app.inject({ url: '/api/v1/device/upgrades', headers: { authorization: `Bearer ${another.token}` } })).json().data.jobs.length, 0);
  const report = (installedVersion: number) => f.app.inject({ method: 'POST', url: '/api/v1/device/upgrades/receipts', headers, payload: { receipts: [{ id: jobId, version: 1, state: 'SUCCEEDED', result: 'installed', installedVersion, installedSigners: ['c'.repeat(64)] }] } });
  assert.equal((await report(1)).statusCode, 400); assert.equal((await report(2)).statusCode, 200);
  assert.equal((await f.app.inject({ url: '/api/v1/device/upgrades', headers })).json().data.jobs.length, 0);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { fixture, candidate } from './fixture.ts';

test('challenge is single use and another key cannot take over an installation', async t => {
  const f = await fixture(t);
  const payload = await f.registration();
  const request = { method: 'POST' as const, url: '/api/v1/device/register', headers: { 'x-enrollment-key': f.enrollmentKey }, payload };
  const registered = await f.app.inject(request);
  assert.equal(registered.statusCode, 200);
  assert.equal((await f.app.inject(request)).statusCode, 401);
  const changedKey = await f.registration(payload.installId);
  assert.equal((await f.app.inject({ ...request, payload: changedKey })).statusCode, 409);
  const bad = await f.registration();
  assert.equal((await f.app.inject({ ...request, payload: { ...bad, signature: randomBytes(64).toString('base64') } })).statusCode, 401);
});

test('two device credentials have isolated configurations and cannot use administrator APIs', async t => {
  const f = await fixture(t);
  const first = await f.register(); const second = await f.register();
  const sync = (token: string, value: unknown) => f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${token}` }, payload: { candidate: value } });
  assert.equal((await sync(first.token, candidate(100, true))).statusCode, 200);
  assert.equal((await sync(second.token, null)).json().data.current, null);
  assert.equal((await sync(second.token, candidate(200, false))).json().data.current.config.enabled, false);
  assert.equal((await sync(first.token, null)).json().data.current.config.enabled, true);
  assert.equal((await f.app.inject({ url: '/api/v1/admin/devices', headers: { authorization: `Bearer ${first.token}` } })).statusCode, 401);
});

test('configuration input rejects privileged fields, equal endpoints and mutation ID reuse', async t => {
  const f = await fixture(t); const device = await f.register();
  const sync = (value: unknown) => f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${device.token}` }, payload: { candidate: value } });
  const original = candidate(100, true);
  assert.equal((await sync({ ...original, config: { ...original.config, initialized: true } })).statusCode, 400);
  assert.equal((await sync({ ...original, config: { ...original.config, schedule: [{ startMinute: 1, endMinute: 1 }, ...Array(6).fill(null)] } })).statusCode, 400);
  assert.equal((await sync(original)).statusCode, 200);
  assert.equal((await sync({ ...original, config: { ...original.config, enabled: false } })).statusCode, 409);
  assert.equal((await sync(null)).json().data.current.config.enabled, true);
});

test('web edits wait for the initial device configuration', async t => {
  const f = await fixture(t); const device = await f.register(); const headers = { ...await f.admin(), 'x-mutation-id': randomUUID() };
  const url = `/api/v1/admin/devices/${device.deviceId}/config`;
  assert.equal((await f.app.inject({ method: 'PUT', url, headers, payload: candidate(1, true).config })).statusCode, 409);
  await f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${device.token}` }, payload: { candidate: candidate(1, true) } });
  const response = await f.app.inject({ method: 'PUT', url, headers, payload: candidate(2, false).config });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().data.config.enabled, false);
});

test('web save response loss and retry cannot supersede a later device edit', async t => {
  let now = 1000;
  const f = await fixture(t, { now: () => now }); const device = await f.register();
  const sync = (value: unknown) => f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${device.token}` }, payload: { candidate: value } });
  await sync(candidate(1, true));
  const headers = { ...await f.admin(), 'x-mutation-id': randomUUID() };
  const request = { method: 'PUT' as const, url: `/api/v1/admin/devices/${device.deviceId}/config`, headers, payload: candidate(1, false).config };
  assert.equal((await f.app.inject(request)).json().data.config.enabled, false);
  now = 2000; await sync(candidate(now, true));
  now = 3000;
  assert.equal((await f.app.inject(request)).json().data.config.enabled, true);
  assert.equal((await sync(null)).json().data.current.config.enabled, true);
});

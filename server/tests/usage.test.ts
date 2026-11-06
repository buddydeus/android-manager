import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './fixture.ts';
const midnight = Date.parse('2026-11-06T00:00:00+08:00');
function session(start = midnight - 1000, duration = 3000) {
  return { id: randomUUID(), recordVersion: 2, bootKey: randomUUID(), bootId: 1, observedAtMs: start, startedAtMs: start, endedAtMs: start + duration, durationMs: duration, quality: 'COMPLETE', source: 'LIVE', clockAdjusted: false };
}
test('usage upsert keeps corrections and daily totals split at Shanghai midnight', async t => {
  const f = await fixture(t, { now: () => midnight + 86400000 }); const d = await f.register();
  const headers = { authorization: `Bearer ${d.token}` }; const s = session();
  const send = (row: unknown) => f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers, payload: { sessions: [row], gaps: [] } });
  assert.equal((await send(s)).statusCode, 200);
  assert.equal((await send({ ...s, recordVersion: 1, endedAtMs: null, durationMs: null, quality: 'IN_PROGRESS' })).statusCode, 200);
  const admin = await f.admin();
  const get = (date: string) => f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/usage?date=${date}`, headers: admin });
  const today = await get('2026-11-06'); assert.equal(today.statusCode, 200);
  assert.equal(today.json().data.completeDurationMs, 2000); assert.equal(today.json().data.sessions[0].recordVersion, 2);
  assert.equal((await get('2026-11-05')).json().data.completeDurationMs, 1000);
  assert.equal((await get('2026-02-30')).statusCode, 400);
  assert.equal((await send({ ...s, durationMs: 10 })).statusCode, 409);
});
test('incomplete sessions and coverage gaps warn without inventing duration', async t => {
  const f = await fixture(t, { now: () => midnight + 86400000 }); const d = await f.register();
  const s = { ...session(midnight + 100), quality: 'MISSING_END', endedAtMs: null, durationMs: null };
  const gap = { id: randomUUID(), recordVersion: 1, startedAtMs: midnight, endedAtMs: midnight + 5000, reason: 'MONITOR_INTERRUPTED', resolved: false };
  const saved = await f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers: { authorization: `Bearer ${d.token}` }, payload: { sessions: [s], gaps: [gap] } });
  assert.equal(saved.statusCode, 200);
  const result = (await f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-06`, headers: await f.admin() })).json().data;
  assert.equal(result.completeDurationMs, 0); assert.equal(result.incompleteCount, 1); assert.equal(result.gaps.length, 1);
});
test('retention removes expired incomplete records but preserves the ninety-day boundary', async t => {
  const f = await fixture(t, { now: () => midnight }); const d = await f.register();
  const cutoff = midnight - 90 * 86400000;
  const expired = { ...session(cutoff - 2000, 1000), quality: 'MISSING_END', endedAtMs: null, durationMs: null }; const boundary = session(cutoff - 1000, 1000);
  const response = await f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers: { authorization: `Bearer ${d.token}` }, payload: { sessions: [expired, boundary], gaps: [] } });
  assert.equal(response.statusCode, 200);
  const date = new Date(cutoff - 1 + 8 * 3600000).toISOString().slice(0, 10);
  const data = (await f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/usage?date=${date}`, headers: await f.admin() })).json().data;
  assert.equal(data.total, 1); assert.equal(data.sessions[0].id, boundary.id);
});

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

test('usage pages order by newest start, break ties consistently and put missing starts last', async t => {
  const f = await fixture(t, { now: () => midnight + 86400000 }); const d = await f.register();
  const oldest = { ...session(midnight + 100), id: '00000000-0000-4000-8000-000000000001' };
  const newest = { ...session(midnight + 300), id: '00000000-0000-4000-8000-000000000002' };
  const tied = { ...newest, id: '00000000-0000-4000-8000-000000000003' };
  const missing = { ...session(midnight + 900), startedAtMs: null, durationMs: null, quality: 'MISSING_START' };
  const saved = await f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers: { authorization: `Bearer ${d.token}` }, payload: { sessions: [oldest, missing, newest, tied], gaps: [] } });
  assert.equal(saved.statusCode, 200);
  const headers = await f.admin();
  const ids: string[] = [];
  for (const page of [1, 2]) {
    const response = await f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-06&limit=2&page=${page}`, headers });
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().data.total, 4);
    ids.push(...response.json().data.sessions.map((s: { id: string }) => s.id));
  }
  assert.deepEqual(ids, [tied.id, newest.id, oldest.id, missing.id]);
});

test('clearing a day removes its sessions, gaps and totals while preserving other dates and devices', async t => {
  const now = midnight + 12 * 3600000;
  const f = await fixture(t, { now: () => now }); const d = await f.register(); const other = await f.register();
  const s = session(midnight - 1000, 3000);
  const gap = { id: randomUUID(), recordVersion: 1, startedAtMs: midnight - 1000, endedAtMs: midnight + 2000, reason: 'MONITOR_INTERRUPTED', resolved: false };
  for (const device of [d, other]) assert.equal((await f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers: { authorization: `Bearer ${device.token}` }, payload: { sessions: [s], gaps: [gap] } })).statusCode, 200);
  const admin = await f.admin();const url = `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-06`;
  assert.equal((await f.app.inject({ method: 'DELETE', url, headers: admin })).statusCode, 200);
  const cleared = (await f.app.inject({ url, headers: admin })).json().data;
  assert.equal(cleared.total, 0);assert.equal(cleared.completeDurationMs, 0);assert.equal(cleared.incompleteCount, 0);assert.equal(cleared.activeCount, 0);assert.deepEqual(cleared.gaps, []);
  const previous = (await f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-05`, headers: admin })).json().data;
  assert.equal(previous.total, 1);assert.equal(previous.completeDurationMs, 1000);assert.equal(previous.gaps.length, 1);
  const untouched = (await f.app.inject({ url: `/api/v1/admin/devices/${other.deviceId}/usage?date=2026-11-06`, headers: admin })).json().data;
  assert.equal(untouched.total, 1);assert.equal(untouched.gaps.length, 1);
});

test('cleared records stay cleared through retransmissions and corrections, but new use is visible', async t => {
  let now = midnight + 10000;
  const f = await fixture(t, { now: () => now });const d = await f.register();
  const old = session(midnight + 100);const futureClock = session(midnight + 50000);
  const headers = { authorization: `Bearer ${d.token}` };
  const send = (sessions: unknown[], gaps: unknown[] = []) => f.app.inject({ method: 'POST', url: '/api/v1/device/usage', headers, payload: { sessions, gaps } });
  assert.equal((await send([old, futureClock])).statusCode, 200);
  const admin = await f.admin();const url = `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-06`;
  assert.equal((await f.app.inject({ method: 'DELETE', url, headers: admin })).statusCode, 200);
  const offline = session(midnight + 500);const missingOffline = { ...session(midnight + 600), startedAtMs: null, endedAtMs: midnight + 16000, durationMs: null, quality: 'MISSING_START' };const active = { ...old, recordVersion: 3, quality: 'IN_PROGRESS', endedAtMs: null, durationMs: null };
  const corrected = { ...futureClock, recordVersion: 3, startedAtMs: midnight + 60000, endedAtMs: midnight + 63000 };
  const gap = { id: randomUUID(), recordVersion: 1, startedAtMs: midnight + 500, endedAtMs: midnight + 15000, reason: 'MONITOR_INTERRUPTED', resolved: false };
  assert.equal((await send([old, offline, missingOffline, active, corrected], [gap])).statusCode, 200);
  assert.equal((await f.app.inject({ url, headers: admin })).json().data.total, 0);
  const lateCorrection = { ...offline, recordVersion: 3, startedAtMs: midnight + 40000, endedAtMs: midnight + 43000 };
  const gapCorrection = { ...gap, recordVersion: 2, startedAtMs: midnight + 12000, endedAtMs: midnight + 15000 };
  assert.equal((await send([lateCorrection], [gapCorrection])).statusCode, 200);
  const correctedView = (await f.app.inject({ url, headers: admin })).json().data;
  assert.equal(correctedView.total, 0);assert.deepEqual(correctedView.gaps, []);
  const fresh = session(midnight + 11000);
  assert.equal((await send([fresh])).statusCode, 200);
  const result = (await f.app.inject({ url, headers: admin })).json().data;
  assert.deepEqual(result.sessions.map((s: { id: string }) => s.id), [fresh.id]);assert.equal(result.completeDurationMs, 3000);assert.deepEqual(result.gaps, []);
  now += 20000;
  assert.equal((await f.app.inject({ method: 'DELETE', url, headers: admin })).statusCode, 200);
  assert.equal((await send([corrected, fresh])).statusCode, 200);
  assert.equal((await f.app.inject({ url, headers: admin })).json().data.total, 0);
});

test('clear-day requires administrator CSRF and a valid calendar date', async t => {
  const f = await fixture(t);const d = await f.register();const admin = await f.admin();
  const url = `/api/v1/admin/devices/${d.deviceId}/usage?date=2026-11-06`;
  assert.equal((await f.app.inject({ method: 'DELETE', url })).statusCode, 401);
  assert.equal((await f.app.inject({ method: 'DELETE', url, headers: { cookie: admin.cookie, origin: admin.origin } })).statusCode, 403);
  assert.equal((await f.app.inject({ method: 'DELETE', url, headers: { ...admin, origin: 'https://untrusted.example' } })).statusCode, 403);
  assert.equal((await f.app.inject({ method: 'DELETE', url: url.replace('2026-11-06', '2026-02-30'), headers: admin })).statusCode, 400);
  const earlyDate = url.replace('2026-11-06', '1969-12-31');
  assert.equal((await f.app.inject({ method: 'DELETE', url: earlyDate, headers: admin })).statusCode, 200);
  assert.equal((await f.app.inject({ url: earlyDate, headers: admin })).statusCode, 200);
});

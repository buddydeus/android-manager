import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate } from 'node:timers/promises';
import { fixture } from './fixture.ts';
import { candidate } from './fixture.ts';
import { randomUUID } from 'node:crypto';

test('authenticated channel acknowledges heartbeat and online state disappears on disconnect', async t => {
  const f = await fixture(t); const device = await f.register();
  const headers = await f.admin(); await f.app.ready();
  const ws = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${device.token}` } });
  try {
    const acknowledged = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('heartbeat was not acknowledged')), 3000);
      ws.on('message', raw => {
        const message = JSON.parse(raw.toString()) as { type: string };
        if (message.type === 'heartbeat.ack') { clearTimeout(timer); resolve(); }
      });
    });
    ws.send(JSON.stringify({ type: 'heartbeat', bootId: 3 })); await acknowledged;
    const online = (await f.app.inject({ url: '/api/v1/admin/devices', headers })).json().data[0].online;
    assert.equal(online, true);
  } finally { ws.terminate(); }
  await setImmediate();
  assert.equal((await f.app.inject({ url: '/api/v1/admin/devices', headers })).json().data[0].online, false);
});

test('configuration notification saved before a channel connects is replayed after its first heartbeat', async t => {
  const f = await fixture(t); const device = await f.register();
  await f.app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${device.token}` }, payload: { candidate: candidate(1, true) } });
  const headers = { ...await f.admin(), 'x-mutation-id': randomUUID() };
  const saved = await f.app.inject({ method: 'PUT', url: `/api/v1/admin/devices/${device.deviceId}/config`, headers, payload: candidate(1, false).config });
  assert.equal(saved.statusCode, 200);
  const ws = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${device.token}` } });
  try {
    const notified = new Promise<number>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('offline notification was not replayed')), 2000);
      ws.on('message', raw => {
        const message = JSON.parse(raw.toString()) as { type: string; serverRevision: number };
        if (message.type === 'config.changed') { clearTimeout(timer); resolve(message.serverRevision); }
      });
    });
    ws.send(JSON.stringify({ type: 'heartbeat', bootId: 3 }));
    assert.equal(await notified, saved.json().data.serverRevision);
  } finally { ws.terminate(); }
});

test('device reports version and scoped policy result without changing synchronized configuration', async t => {
  const f = await fixture(t); const device = await f.register();
  const response = await f.app.inject({ method: 'POST', url: '/api/v1/device/status', headers: { authorization: `Bearer ${device.token}` }, payload: {
    versionCode: 2, versionName: '2.0', apiLevel: 36, bootId: 5,
    status: { configMutationId: null, configRevision: 1, policyResult: '已核验', alarmStatus: '已安排' },
  } });
  assert.equal(response.statusCode, 200);
  const headers = await f.admin();
  const detail = (await f.app.inject({ url: `/api/v1/admin/devices/${device.deviceId}`, headers })).json().data;
  assert.equal(detail.versionCode, 2); assert.equal(detail.status.policyResult, '已核验');
  assert.equal((await f.app.inject({ url: `/api/v1/admin/devices/${device.deviceId}/config`, headers })).json().data, null);
});

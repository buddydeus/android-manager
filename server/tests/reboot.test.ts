import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './fixture.ts';
function message(ws: { on: Function; off: Function }, type: string): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Missing ${type}`)), 2000);
    const listener = (raw: { toString(): string }) => { const v = JSON.parse(raw.toString()) as Record<string, unknown>; if (v.type === type) { clearTimeout(timer); ws.off('message', listener); resolve(v); } };
    ws.on('message', listener);
  });
}
test('reboot requires a live heartbeat, explicit confirmation and a one-time permit on the same channel', async t => {
  const f = await fixture(t); const d = await f.register(); const admin = await f.admin();
  const commandId = randomUUID(); const url = `/api/v1/admin/devices/${d.deviceId}/reboot`;
  const issue = (confirmed = true) => f.app.inject({ method: 'POST', url, headers: admin, payload: { commandId, confirmed } });
  assert.equal((await issue()).statusCode, 409);
  const ws = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${d.token}` } });
  try {
    const heartbeat = message(ws, 'heartbeat.ack'); ws.send(JSON.stringify({ type: 'heartbeat', bootId: 4 })); const ack = await heartbeat;
    assert.equal((await issue(false)).statusCode, 400);
    const notified = message(ws, 'reboot.request'); assert.equal((await issue()).statusCode, 200); const request = await notified;
    assert.equal(request.commandId, commandId);
    const accepted = await f.app.inject({ method: 'POST', url: '/api/v1/device/commands/receipts', headers: { authorization: `Bearer ${d.token}` }, payload: { receipts: [{ id: commandId, version: 1, state: 'ACCEPTED', result: 'accepted before permit request' }] } });
    assert.equal(accepted.statusCode, 200);
    const permit = message(ws, 'reboot.permit'); ws.send(JSON.stringify({ type: 'reboot.permit.request', commandId, connectionId: ack.connectionId })); assert.equal((await permit).allowed, true);
    const second = message(ws, 'reboot.permit'); ws.send(JSON.stringify({ type: 'reboot.permit.request', commandId, connectionId: ack.connectionId })); assert.equal((await second).allowed, false);
    assert.equal((await issue()).json().data.id, commandId);
  } finally { ws.terminate(); }
});
test('reboot permits are never recovered from a persisted command after reconnect', async t => {
  const f = await fixture(t); const d = await f.register(); const admin = await f.admin();
  const ws = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${d.token}` } });
  let p = message(ws, 'heartbeat.ack'); ws.send(JSON.stringify({ type: 'heartbeat', bootId: 5 })); await p;
  const commandId = randomUUID();
  await f.app.inject({ method: 'POST', url: `/api/v1/admin/devices/${d.deviceId}/reboot`, headers: admin, payload: { commandId, confirmed: true } }); ws.terminate();
  const next = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${d.token}` } });
  try {
    p = message(next, 'heartbeat.ack'); next.send(JSON.stringify({ type: 'heartbeat', bootId: 5 })); const ack = await p;
    p = message(next, 'reboot.permit'); next.send(JSON.stringify({ type: 'reboot.permit.request', commandId, connectionId: ack.connectionId })); assert.equal((await p).allowed, false);
  } finally { next.terminate(); }
});
test('permit deadline uses elapsed time and later boot is only reported as observation', async t => {
  let mono = 100; const f = await fixture(t, { monotonic: () => mono }); const d = await f.register(); const admin = await f.admin();
  const ws = await f.app.injectWS('/api/v1/device/channel', { headers: { authorization: `Bearer ${d.token}` } });
  try {
    let p = message(ws, 'heartbeat.ack'); ws.send(JSON.stringify({ type: 'heartbeat', bootId: 7 })); const ack = await p;
    const commandId = randomUUID();
    await f.app.inject({ method: 'POST', url: `/api/v1/admin/devices/${d.deviceId}/reboot`, headers: admin, payload: { commandId, confirmed: true } });
    mono += 10001; p = message(ws, 'reboot.permit'); ws.send(JSON.stringify({ type: 'reboot.permit.request', commandId, connectionId: ack.connectionId })); assert.equal((await p).allowed, false);
    const allowedId = randomUUID();
    await f.app.inject({ method: 'POST', url: `/api/v1/admin/devices/${d.deviceId}/reboot`, headers: admin, payload: { commandId: allowedId, confirmed: true } });
    p = message(ws, 'reboot.permit'); ws.send(JSON.stringify({ type: 'reboot.permit.request', commandId: allowedId, connectionId: ack.connectionId })); assert.equal((await p).allowed, true);
    const receipt = await f.app.inject({ method: 'POST', url: '/api/v1/device/commands/receipts', headers: { authorization: `Bearer ${d.token}` }, payload: { receipts: [{ id: allowedId, version: 2, state: 'ATTEMPTED', result: 'attempted' }] } }); assert.equal(receipt.statusCode, 200);
    p = message(ws, 'heartbeat.ack'); ws.send(JSON.stringify({ type: 'heartbeat', bootId: 8 })); await p;
    const rows = (await f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/commands`, headers: admin })).json().data;
    assert.equal(rows[0].state, 'OBSERVED_LATER_BOOT');
  } finally { ws.terminate(); }
});

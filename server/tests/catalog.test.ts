import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from './fixture.ts';
test('incomplete application snapshots never replace the previous complete catalog', async t => {
  const f = await fixture(t); const d = await f.register(); const headers = { authorization: `Bearer ${d.token}` };
  const first = randomUUID(); const second = randomUUID();
  const entry = { packageName: 'example.game', label: 'Game', installed: true, system: false, protection: '' };
  const send = (snapshotId: string, page: number, pages: number, entries: unknown[]) => f.app.inject({ method: 'POST', url: '/api/v1/device/apps', headers, payload: { snapshotId, page, pages, entries } });
  assert.equal((await send(first, 0, 1, [entry])).statusCode, 200);
  assert.equal((await send(second, 0, 2, [{ ...entry, label: 'New' }])).statusCode, 200);
  const admin = await f.admin(); const get = () => f.app.inject({ url: `/api/v1/admin/devices/${d.deviceId}/apps`, headers: admin });
  assert.equal((await get()).json().data.entries[0].label, 'Game');
  assert.equal((await send(second, 1, 2, [])).statusCode, 200);
  assert.equal((await get()).json().data.entries[0].label, 'New');
  assert.equal((await send(second, 0, 2, [entry])).statusCode, 409);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { createApp } from '../api/src/app.ts';

const origin = 'https://ams.opt.work';
test('unknown device credentials cannot read configuration', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ams-test-'));
  const app = await createApp({ directory, enrollmentKey: randomBytes(32).toString('hex'), origin });
  try {
    const response = await app.inject({ method: 'POST', url: '/api/v1/device/sync', payload: {}, headers: { authorization: 'Bearer '+randomUUID() } });
    assert.equal(response.statusCode, 401);
  } finally { await app.close(); await rm(directory, { recursive: true, force: true }); }
});

test('registration is idempotent and sync chooses actual save time including equal-time server priority', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ams-test-'));
  const enrollmentKey = randomBytes(32).toString('hex');
  const app = await createApp({ directory, enrollmentKey, origin });
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKey = keys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  const installId = randomUUID();
  async function register(): Promise<{ deviceId: string; token: string }> {
    const challengeResponse = await app.inject({ method: 'POST', url: '/api/v1/device/challenges', headers: { 'x-enrollment-key': enrollmentKey }, payload: { installId, publicKey } });
    assert.equal(challengeResponse.statusCode, 200);
    const c = challengeResponse.json().data as { challengeId: string; nonce: string };
    const signature = sign('sha256', Buffer.from(`${c.challengeId}\n${c.nonce}\n${installId}\n${publicKey}`), keys.privateKey).toString('base64');
    const response = await app.inject({ method: 'POST', url: '/api/v1/device/register', headers: { 'x-enrollment-key': enrollmentKey }, payload: { installId, publicKey, challengeId: c.challengeId, signature, model: 'test', apiLevel: 36, versionCode: 1, versionName: '1.0' } });
    assert.equal(response.statusCode, 200);
    return response.json().data;
  }
  try {
    let identity = await register();
    const candidate = (time: number, enabled: boolean) => ({ mutationId: randomUUID(), modifiedAtMs: time, origin: 'LOCAL', timeQuality: 'CALIBRATED', config: { enabled, blacklist: [], schedule: Array(7).fill(null) } });
    const sync = (value: unknown) => app.inject({ method: 'POST', url: '/api/v1/device/sync', headers: { authorization: `Bearer ${identity.token}` }, payload: { candidate: value } });
    const first = candidate(2000, true);
    assert.equal((await sync(first)).json().data.current.config.enabled, true);
    assert.equal((await sync(candidate(1000, false))).json().data.current.config.enabled, true);
    assert.equal((await sync(candidate(2000, false))).json().data.current.config.enabled, true);
    assert.equal((await sync(candidate(3000, false))).json().data.current.config.enabled, false);
    assert.equal((await sync(first)).json().data.current.config.enabled, false);
    const again = await register();
    assert.equal(again.deviceId, identity.deviceId);
    identity = again;
    assert.equal((await sync(null)).json().data.current.config.enabled, false);
  } finally { await app.close(); await rm(directory, { recursive: true, force: true }); }
});

import type { TestContext } from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { createApp } from '../api/src/app.ts';
import { provisionAdmin } from '../api/src/auth.ts';

export async function fixture(t: TestContext, options: { now?: () => number; monotonic?: () => number; buildTools?: string } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'ams-fixture-'));
  const enrollmentKey = randomBytes(32).toString('hex');
  const password = randomBytes(24).toString('base64url');
  const origin = 'https://ams.opt.work';
  provisionAdmin(directory, 'operator', password);
  const app = await createApp({ directory, enrollmentKey, origin, ...options });
  t.after(async () => { await app.close(); await rm(directory, { recursive: true, force: true }); });
  async function registration(installId: string = randomUUID()) {
    const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const publicKey = keys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
    const challenge = (await app.inject({ method: 'POST', url: '/api/v1/device/challenges', headers: { 'x-enrollment-key': enrollmentKey }, payload: { installId, publicKey } })).json().data as { challengeId: string; nonce: string };
    const signature = sign('sha256', Buffer.from(`${challenge.challengeId}\n${challenge.nonce}\n${installId}\n${publicKey}`), keys.privateKey).toString('base64');
    return { installId, publicKey, signature, challengeId: challenge.challengeId, model: 'test', apiLevel: 36, versionCode: 1, versionName: '1.0' };
  }
  async function register(installId?: string) {
    const payload = await registration(installId);
    const response = await app.inject({ method: 'POST', url: '/api/v1/device/register', headers: { 'x-enrollment-key': enrollmentKey }, payload });
    if (response.statusCode !== 200) throw new Error('fixture registration failed');
    return response.json().data as { deviceId: string; token: string };
  }
  async function admin() {
    const response = await app.inject({ method: 'POST', url: '/api/v1/admin/login', headers: { origin }, payload: { username: 'operator', password } });
    if (response.statusCode !== 200) throw new Error('Administrator authentication rejected');
    return { origin, cookie: String(response.headers['set-cookie']).split(';')[0], 'x-csrf-token': response.json().data.csrf as string };
  }
  return { app, directory, enrollmentKey, registration, register, admin };
}
export function candidate(modifiedAtMs: number, enabled: boolean) {
  return { mutationId: randomUUID(), modifiedAtMs, origin: 'LOCAL', timeQuality: 'CALIBRATED', config: { enabled, blacklist: [], schedule: Array(7).fill(null) } };
}

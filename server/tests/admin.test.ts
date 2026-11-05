import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createApp } from '../api/src/app.ts';
import { provisionAdmin } from '../api/src/auth.ts';

test('administrator session has secure cookie, CSRF checks and expires after idle timeout', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ams-admin-'));
  const password = randomBytes(24).toString('base64url');
  provisionAdmin(directory, 'operator', password);
  let now = 1_000_000;
  const origin = 'https://ams.opt.work';
  const app = await createApp({ directory, enrollmentKey: randomBytes(32).toString('hex'), origin, now: () => now });
  try {
    const response = await app.inject({ method: 'POST', url: '/api/v1/admin/login', headers: { origin }, payload: { username: 'operator', password } });
    assert.equal(response.statusCode, 200);
    const cookie = response.headers['set-cookie'];
    assert.equal(typeof cookie, 'string');
    assert.match(String(cookie), /HttpOnly/); assert.match(String(cookie), /Secure/); assert.match(String(cookie), /SameSite=Strict/);
    const headers = { cookie: String(cookie).split(';')[0], origin };
    assert.equal((await app.inject({ url: '/api/v1/admin/devices', headers })).statusCode, 200);
    assert.equal((await app.inject({ method: 'POST', url: '/api/v1/admin/logout', headers })).statusCode, 403);
    const csrf = response.json().data.csrf as string;
    assert.equal((await app.inject({ method: 'POST', url: '/api/v1/admin/logout', headers: { ...headers, origin: 'https://unrelated.invalid', 'x-csrf-token': csrf } })).statusCode, 403);
    now += 31 * 60_000;
    assert.equal((await app.inject({ url: '/api/v1/admin/devices', headers })).statusCode, 401);
  } finally { await app.close(); await rm(directory, { recursive: true, force: true }); }
});

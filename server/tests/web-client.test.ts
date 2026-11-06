import { test } from 'node:test';
import assert from 'node:assert/strict';
import { z } from 'zod';
import { ApiClient } from '../web/src/api.ts';
test('a delayed unauthorized response from an old session cannot clear a newly established session', async () => {
  const original = globalThis.fetch; let release: (response: Response) => void = () => {};
  globalThis.fetch = () => new Promise<Response>(resolve => { release = resolve; });
  const api = new ApiClient();api.csrf = 'old-session';let expired = 0;api.onExpired = () => { expired++;api.csrf = ''; };
  try { const old = api.data('/session', z.object({ csrf: z.string() }));api.csrf = 'new-session';release(new Response(JSON.stringify({ message: 'expired' }), { status: 401 }));await assert.rejects(old);assert.equal(expired, 0);assert.equal(api.csrf, 'new-session'); }
  finally { globalThis.fetch = original; }
});

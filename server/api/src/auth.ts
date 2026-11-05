import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { ApiError } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { digest } from './device.ts';
import { Store } from './db.ts';

const loginSchema = z.object({ username: z.string().min(1).max(100), password: z.string().min(12).max(256) }).strict();
const kdfParameters = { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 } as const;
const adminSchema = z.object({
  username: z.string(), salt: z.string().regex(/^[0-9a-f]{32}$/), verifier: z.string().regex(/^[0-9a-f]{64}$/),
  kdf: z.literal('scrypt'), kdfVersion: z.literal(1), N: z.literal(32768), r: z.literal(8), p: z.literal(1), keyLength: z.literal(32),
});
const sessionSchema = z.object({ csrf: z.string(), createdAt: z.number(), lastAt: z.number() });
export function provisionAdmin(directory: string, username: string, password: string): void {
  loginSchema.parse({ username, password });
  const store = new Store(directory);
  try {
    store.transaction(() => {
      if (store.get('admins', 'administrator', adminSchema)) throw new Error('管理员已存在，不能重复初始化');
      const salt = randomBytes(16).toString('hex');
      const verifier = scryptSync(password, salt, 32, kdfParameters).toString('hex');
      store.put('admins', 'administrator', '', { username, salt, verifier, kdf: 'scrypt', kdfVersion: 1, N: kdfParameters.N, r: kdfParameters.r, p: kdfParameters.p, keyLength: 32 });
    });
  } finally { store.close(); }
}
export function requireAdmin(ctx: Context, request: FastifyRequest, write = false): z.infer<typeof sessionSchema> {
  const token = request.cookies.ams_session;
  const key = token ? digest(token) : '';
  const session = key ? ctx.store.get('sessions', key, sessionSchema) : null;
  const now = ctx.now();
  if (!session || now - session.lastAt >= 30 * 60_000 || now - session.createdAt >= 12 * 60 * 60_000) {
    if (session) ctx.store.delete('sessions', key);
    throw new ApiError(401, 'ADMIN_AUTH', '请登录管理员账号');
  }
  if (write && (request.headers.origin !== ctx.origin || request.headers['x-csrf-token'] !== session.csrf)) throw new ApiError(403, 'CSRF', '请求来源校验失败，请刷新页面');
  ctx.store.put('sessions', key, '', { ...session, lastAt: now });
  return session;
}
export function authRoutes(app: FastifyInstance, ctx: Context): void {
  const attempts = new Map<string, { count: number; until: number }>();
  app.post('/api/v1/admin/login', (request, reply) => {
    if (request.headers.origin !== ctx.origin) throw new ApiError(403, 'ORIGIN', '请求来源校验失败');
    const now = ctx.now();
    const previous = attempts.get(request.ip);
    const attempt = previous && previous.until > now ? previous : { count: 0, until: now + 5 * 60_000 };
    if (attempt.count >= 5) throw new ApiError(429, 'LOGIN_PAUSED', '登录尝试过多，请稍后再试');
    if (attempts.size >= 10_000) attempts.delete(attempts.keys().next().value ?? '');
    attempts.set(request.ip, { ...attempt, count: attempt.count + 1 });
    const input = loginSchema.parse(request.body);
    const admin = ctx.store.get('admins', 'administrator', adminSchema);
    const salt = admin?.salt ?? randomBytes(16).toString('hex');
    const computed = scryptSync(input.password, salt, admin?.keyLength ?? 32, admin ? { N: admin.N, r: admin.r, p: admin.p, maxmem: kdfParameters.maxmem } : kdfParameters);
    if (!admin || !timingSafeEqual(computed, Buffer.from(admin.verifier, 'hex')) || input.username !== admin.username) throw new ApiError(401, 'LOGIN_FAILED', '账号或密码错误');
    attempts.delete(request.ip);
    ctx.store.db.prepare("DELETE FROM sessions WHERE json_extract(data,'$.lastAt') < ? OR json_extract(data,'$.createdAt') < ?").run(now - 30 * 60_000, now - 12 * 60 * 60_000);
    const token = randomBytes(32).toString('base64url');
    const session = { csrf: randomBytes(32).toString('base64url'), createdAt: now, lastAt: now };
    ctx.store.put('sessions', digest(token), '', session);
    reply.setCookie('ams_session', token, { secure: true, httpOnly: true, sameSite: 'strict', path: '/api/v1/admin', maxAge: 12 * 60 * 60 });
    return { data: { csrf: session.csrf } };
  });
  app.get('/api/v1/admin/session', request => ({ data: { csrf: requireAdmin(ctx, request).csrf } }));
  app.post('/api/v1/admin/logout', (request, reply) => {
    requireAdmin(ctx, request, true);
    ctx.store.delete('sessions', digest(request.cookies.ams_session ?? ''));
    reply.clearCookie('ams_session', { path: '/api/v1/admin' });
    return { data: { loggedOut: true } };
  });
}

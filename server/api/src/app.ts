import Fastify from 'fastify';
import fastifyStatic from '@fastify/static';
import { releaseRoutes } from './releases.ts';
import { rebootRoutes } from './reboot.ts';
import { usageRoutes } from './usage.ts';
import { catalogRoutes } from './catalog.ts';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ApiError } from '../../contracts/index.ts';
import { Store } from './db.ts';
import { deviceRoutes, digest } from './device.ts';
import type { Context } from './device.ts';
import cookie from '@fastify/cookie';
import { authRoutes } from './auth.ts';
import { adminRoutes } from './admin.ts';
import websocket from '@fastify/websocket';
import { RealtimeHub } from './realtime.ts';

export interface AppOptions { directory: string; enrollmentKey: string; origin: string; now?: () => number; monotonic?: () => number; buildTools?: string; webRoot?: string; trustProxy?: string[] }
export async function createApp(options: AppOptions): Promise<FastifyInstance> {
  if (options.enrollmentKey.length < 32 || new URL(options.origin).protocol !== 'https:') throw new Error('Invalid deployment configuration');
  const app = Fastify({ bodyLimit: 1024 * 1024, trustProxy: options.trustProxy ?? false });
  const store = new Store(options.directory);
  const ctx: Context = { store, now: options.now ?? Date.now, monotonic: options.monotonic ?? (() => performance.now()), enrollmentHash: digest(options.enrollmentKey), origin: options.origin, notify: () => false, disconnect: () => {} };
  await app.register(cookie);
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });
  ctx.realtime = new RealtimeHub(app, ctx);
  ctx.notify = (id, message) => ctx.realtime?.send(id, message) ?? false;
  ctx.disconnect = id => { ctx.realtime?.disconnect(id); };
  app.addHook('onClose', () => { store.close(); });
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) return reply.code(error.status).send({ errorCode: error.code, message: error.message, requestId: request.id });
    if (error instanceof z.ZodError) return reply.code(400).send({ errorCode: 'INVALID_INPUT', message: '输入格式不正确', requestId: request.id });
    if (error instanceof Error && 'statusCode' in error && typeof error.statusCode === 'number' && error.statusCode >= 400 && error.statusCode < 500) return reply.code(error.statusCode).send({ errorCode: 'INVALID_REQUEST', message: '请求格式或大小不符合限制', requestId: request.id });
    if (error instanceof Error && 'code' in error && (error.code === 'SQLITE_BUSY' || error.code === 'SQLITE_LOCKED')) return reply.header('Retry-After', '1').code(503).send({ errorCode: 'STORAGE_BUSY', message: '存储繁忙，请稍后重试', requestId: request.id });
    request.log.error({ err: error }, 'Request failed');
    return reply.code(500).send({ errorCode: 'INTERNAL', message: '操作失败，请检查服务状态', requestId: request.id });
  });
  app.addHook('onSend', async (_request, reply, payload) => {
    reply.header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    reply.header('X-Content-Type-Options', 'nosniff');reply.header('Referrer-Policy', 'no-referrer');reply.header('Cache-Control', 'no-store');
    return payload;
  });
  app.setNotFoundHandler((request, reply) => reply.code(404).send({ errorCode: 'NOT_FOUND', message: '页面或接口不存在', requestId: request.id }));
  app.get('/health/live', () => ({ status: 'ok' }));
  app.get('/health/ready', () => { store.db.prepare('SELECT 1').get(); return { status: 'ready' }; });
  deviceRoutes(app, ctx);
  authRoutes(app, ctx);
  adminRoutes(app, ctx);
  usageRoutes(app, ctx);
  catalogRoutes(app, ctx);
  rebootRoutes(app, ctx);
  await releaseRoutes(app, ctx, options.directory, options.buildTools ?? process.env.AMS_BUILD_TOOLS ?? '/opt/android-sdk/build-tools/36.0.0');
  if (options.webRoot) await app.register(fastifyStatic, { root: options.webRoot, index: ['index.html'] });
  return app;
}

import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { ApiError, candidateSchema, configSchema, currentSchema, deviceSchema, idSchema } from '../../contracts/index.ts';
import type { Device } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { acceptCandidate } from './device.ts';
import { requireAdmin } from './auth.ts';

export function targetDevice(ctx: Context, request: FastifyRequest): Device {
  const { id } = z.object({ id: idSchema }).parse(request.params);
  const device = ctx.store.get('devices', id, deviceSchema);
  if (!device) throw new ApiError(404, 'DEVICE_NOT_FOUND', '设备不存在');
  return device;
}
export function adminRoutes(app: FastifyInstance, ctx: Context): void {
  app.get('/api/v1/admin/devices', request => {
    requireAdmin(ctx, request);
    const query = z.object({ page: z.coerce.number().int().min(1).max(100_000).default(1), limit: z.coerce.number().int().min(1).max(100).default(50) }).parse(request.query);
    const total = z.object({ total: z.number() }).parse(ctx.store.db.prepare('SELECT count(*) AS total FROM devices').get()).total;
    return { data: ctx.store.list('devices', deviceSchema, undefined, query.limit, (query.page - 1) * query.limit), total, ...query };
  });
  app.get('/api/v1/admin/devices/:id', request => { requireAdmin(ctx, request); return { data: targetDevice(ctx, request) }; });
  app.get('/api/v1/admin/devices/:id/config', request => {
    requireAdmin(ctx, request); const device = targetDevice(ctx, request);
    return { data: ctx.store.get('device_configs', device.id, currentSchema) };
  });
  app.put('/api/v1/admin/devices/:id/config', request => {
    requireAdmin(ctx, request, true); const device = targetDevice(ctx, request);
    if (!ctx.store.get('device_configs', device.id, currentSchema)) throw new ApiError(409, 'BASELINE_PENDING', '等待设备首次上传配置');
    const config = configSchema.parse(request.body);
    const mutationId = idSchema.parse(request.headers['x-mutation-id']);
    const current = ctx.store.transaction(() => {
      const prior = ctx.store.get('config_mutations', `${device.id}:${mutationId}`, candidateSchema);
      if (prior && (prior.origin !== 'REMOTE' || JSON.stringify(prior.config) !== JSON.stringify(config))) throw new ApiError(409, 'MUTATION_REUSE', '同一变更编号内容不一致');
      return acceptCandidate(ctx, device.id, prior ?? { config, mutationId, modifiedAtMs: ctx.now(), timeQuality: 'CALIBRATED', origin: 'REMOTE' });
    });
    ctx.notify(device.id, { type: 'config.changed', serverRevision: current?.serverRevision });
    return { data: current };
  });
}

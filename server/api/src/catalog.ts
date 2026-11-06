import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ApiError, idSchema, packageSchema } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { requireDevice } from './device.ts';
import { requireAdmin } from './auth.ts';
import { targetDevice } from './admin.ts';
const entry = z.object({ packageName: packageSchema, label: z.string().max(1000), installed: z.boolean(), system: z.boolean(), protection: z.string().max(1000) }).strict();
const pageSchema = z.object({ snapshotId: idSchema, page: z.int().min(0).max(24), pages: z.int().min(1).max(25), entries: z.array(entry).max(200) }).strict().refine(x => x.page < x.pages);
const pointerSchema = z.object({ snapshotId: idSchema, pages: z.number(), atMs: z.number() });
export function catalogRoutes(app: FastifyInstance, ctx: Context): void {
  app.post('/api/v1/device/apps', request => {
    const device = requireDevice(ctx, request); const input = pageSchema.parse(request.body);
    return ctx.store.transaction(() => {
      const current = ctx.store.get('app_snapshots', device.id, pointerSchema);
      const stageKey = `${device.id}:stage`;
      const stage = ctx.store.get('app_snapshots', stageKey, pointerSchema);
      if (current?.snapshotId !== input.snapshotId && stage?.snapshotId !== input.snapshotId) {
        // One staged snapshot per device; current remains readable until every new page exists.
        ctx.store.db.prepare('DELETE FROM app_snapshots WHERE deviceId=? AND id!=? AND id NOT LIKE ?').run(device.id, device.id, `${device.id}:${current?.snapshotId ?? 'none'}:%`);
        ctx.store.put('app_snapshots', stageKey, device.id, { snapshotId: input.snapshotId, pages: input.pages, atMs: ctx.now() });
      }
      const pointer = current?.snapshotId === input.snapshotId ? current : ctx.store.get('app_snapshots', stageKey, pointerSchema);
      if (pointer?.pages !== input.pages) throw new ApiError(409, 'SNAPSHOT_REUSE', '目录分页不一致');
      const key = `${device.id}:${input.snapshotId}:${input.page}`; const prior = ctx.store.get('app_snapshots', key, pageSchema);
      if (prior && JSON.stringify(prior) !== JSON.stringify(input)) throw new ApiError(409, 'SNAPSHOT_REUSE', '目录快照内容不一致');
      ctx.store.put('app_snapshots', key, device.id, input);
      let complete = true; const packages = new Set<string>();
      for (let page = 0; page < input.pages; page++) {
        const row = ctx.store.get('app_snapshots', `${device.id}:${input.snapshotId}:${page}`, pageSchema);
        if (!row) { complete = false; continue; }
        for (const app of row.entries) { if (packages.has(app.packageName)) throw new ApiError(400, 'DUPLICATE_PACKAGE', '目录中包含重复应用'); packages.add(app.packageName); }
      }
      if (complete && current?.snapshotId !== input.snapshotId) {
        ctx.store.put('app_snapshots', device.id, device.id, { ...pointer, atMs: ctx.now() });
        ctx.store.db.prepare('DELETE FROM app_snapshots WHERE deviceId=? AND id!=? AND id NOT LIKE ?').run(device.id, device.id, `${device.id}:${input.snapshotId}:%`);
      }
      return { data: { complete, snapshotId: input.snapshotId } };
    });
  });
  app.get('/api/v1/admin/devices/:id/apps', request => {
    requireAdmin(ctx, request); const device = targetDevice(ctx, request);
    const query = z.object({ search: z.string().max(200).default(''), page: z.coerce.number().int().min(1).max(100000).default(1), limit: z.coerce.number().int().min(1).max(200).default(100) }).strict().parse(request.query);
    const current = ctx.store.get('app_snapshots', device.id, pointerSchema);
    const entries: z.infer<typeof entry>[] = [];
    if (current) for (let page = 0; page < current.pages; page++) {
      const row = ctx.store.get('app_snapshots', `${device.id}:${current.snapshotId}:${page}`, pageSchema);
      if (!row) throw new Error('Stored application snapshot is incomplete'); entries.push(...row.entries);
    }
    const filtered = entries.filter(a => `${a.label} ${a.packageName}`.toLowerCase().includes(query.search.toLowerCase())).sort((a, b) => a.packageName.localeCompare(b.packageName));
    return { data: { entries: filtered.slice((query.page - 1) * query.limit, query.page * query.limit), total: filtered.length, atMs: current?.atMs ?? null, ...query } };
  });
}

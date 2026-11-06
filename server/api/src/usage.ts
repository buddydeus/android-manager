import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ApiError, idSchema } from '../../contracts/index.ts';
import { requireDevice } from './device.ts';
import type { Context } from './device.ts';
import { requireAdmin } from './auth.ts';
import { targetDevice } from './admin.ts';

const time = z.int().min(0).max(8_000_000_000_000);
export const usageSchema = z.object({
  id: idSchema, recordVersion: z.int().positive(), bootKey: idSchema, bootId: z.int().min(-1), observedAtMs: time,
  startedAtMs: time.nullable(), endedAtMs: time.nullable(), durationMs: time.nullable(),
  quality: z.enum(['IN_PROGRESS', 'COMPLETE', 'MISSING_START', 'MISSING_END', 'COVERAGE_UNKNOWN', 'SUPERSEDED']),
  source: z.enum(['LIVE', 'HISTORY_ESTIMATED']), clockAdjusted: z.boolean(),
}).strict().superRefine((s, c) => {
  const invalid = s.quality === 'COMPLETE'
    ? s.startedAtMs === null || s.endedAtMs === null || s.durationMs === null
    : s.durationMs !== null || (s.quality === 'IN_PROGRESS' && s.endedAtMs !== null);
  if (invalid) c.addIssue({ code: 'custom', message: 'Invalid usage endpoints' });
});
export const gapSchema = z.object({ id: idSchema, recordVersion: z.int().positive(), startedAtMs: time, endedAtMs: time, reason: z.string().min(1).max(100), resolved: z.boolean() }).strict().refine(s => s.endedAtMs >= s.startedAtMs);
const dayMs = 86400000;
export function shanghaiDay(now: number): number { return Math.floor((now + 8 * 3600000) / dayMs) * dayMs - 8 * 3600000; }
const startExpr = "COALESCE(json_extract(data,'$.startedAtMs'),json_extract(data,'$.endedAtMs'),json_extract(data,'$.observedAtMs'))";
const endExpr = "CASE WHEN json_extract(data,'$.quality')='COMPLETE' THEN json_extract(data,'$.startedAtMs')+json_extract(data,'$.durationMs') ELSE COALESCE(json_extract(data,'$.endedAtMs'),json_extract(data,'$.startedAtMs'),json_extract(data,'$.observedAtMs')) END";
export function cleanupUsage(ctx: Context): void {
  const cutoff = shanghaiDay(ctx.now()) - 90 * dayMs;
  ctx.store.db.prepare(`DELETE FROM usage_sessions WHERE ${endExpr} < ?`).run(cutoff);
  ctx.store.db.prepare("DELETE FROM coverage_gaps WHERE json_extract(data,'$.endedAtMs') < ?").run(cutoff);
}
export function usageRoutes(app: FastifyInstance, ctx: Context): void {
  cleanupUsage(ctx);
  const timer = setInterval(() => cleanupUsage(ctx), dayMs).unref();
  app.addHook('onClose', () => clearInterval(timer));
  app.post('/api/v1/device/usage', request => {
    const device = requireDevice(ctx, request);
    const input = z.object({ sessions: z.array(usageSchema).max(200), gaps: z.array(gapSchema).max(200) }).strict().refine(x => x.sessions.length + x.gaps.length <= 200).parse(request.body);
    ctx.store.transaction(() => {
      for (const s of input.sessions) {
        const key = `${device.id}:${s.id}`; const prior = ctx.store.get('usage_sessions', key, usageSchema);
        if (prior?.recordVersion === s.recordVersion && JSON.stringify(prior) !== JSON.stringify(s)) throw new ApiError(409, 'RECORD_REUSE', '同一记录版本内容不一致');
        if (!prior || prior.recordVersion < s.recordVersion) ctx.store.put('usage_sessions', key, device.id, s);
      }
      for (const s of input.gaps) {
        const key = `${device.id}:${s.id}`; const prior = ctx.store.get('coverage_gaps', key, gapSchema);
        if (prior?.recordVersion === s.recordVersion && JSON.stringify(prior) !== JSON.stringify(s)) throw new ApiError(409, 'RECORD_REUSE', '同一缺口版本内容不一致');
        if (!prior || prior.recordVersion < s.recordVersion) ctx.store.put('coverage_gaps', key, device.id, s);
      }
      cleanupUsage(ctx);
    });
    const ack = (rows: { id: string; recordVersion: number }[]) => rows.map(s => ({ id: s.id, recordVersion: s.recordVersion }));
    return { data: { sessions: ack(input.sessions), gaps: ack(input.gaps) } };
  });
  app.get('/api/v1/admin/devices/:id/usage', request => {
    requireAdmin(ctx, request); const device = targetDevice(ctx, request);
    const query = z.object({ date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), page: z.coerce.number().int().min(1).max(100000).default(1), limit: z.coerce.number().int().min(1).max(200).default(50) }).strict().parse(request.query);
    const begin = Date.parse(`${query.date}T00:00:00+08:00`); const end = begin + dayMs;
    if (!Number.isFinite(begin) || new Date(begin + 8 * 3600000).toISOString().slice(0, 10) !== query.date) throw new ApiError(400, 'INVALID_DATE', '日期无效');
    const where = `deviceId=? AND ${startExpr} < ? AND ${endExpr} >= ? AND json_extract(data,'$.quality')!='SUPERSEDED'`;
    const params = [device.id, end, begin];
    const summary = z.object({ total: z.number(), completeDurationMs: z.number(), incompleteCount: z.number(), activeCount: z.number() }).parse(ctx.store.db.prepare(`SELECT count(*) total,COALESCE(sum(CASE WHEN json_extract(data,'$.quality')='COMPLETE' THEN max(0,min(${endExpr},?)-max(${startExpr},?)) ELSE 0 END),0) completeDurationMs,COALESCE(sum(json_extract(data,'$.quality') NOT IN ('COMPLETE','IN_PROGRESS')),0) incompleteCount,COALESCE(sum(json_extract(data,'$.quality')='IN_PROGRESS'),0) activeCount FROM usage_sessions WHERE ${where}`).get(end, begin, ...params));
    const rows = ctx.store.db.prepare(`SELECT data FROM usage_sessions WHERE ${where} ORDER BY ${startExpr},id LIMIT ? OFFSET ?`).all(...params, query.limit, (query.page - 1) * query.limit);
    const sessions = rows.map(row => usageSchema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown));
    const gaps = ctx.store.db.prepare("SELECT data FROM coverage_gaps WHERE deviceId=? AND json_extract(data,'$.startedAtMs')<? AND json_extract(data,'$.endedAtMs')>=? AND json_extract(data,'$.resolved')=0 ORDER BY json_extract(data,'$.startedAtMs') LIMIT 200").all(device.id, end, begin).map(row => gapSchema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown));
    return { data: { sessions, gaps, ...summary, ...query } };
  });
}

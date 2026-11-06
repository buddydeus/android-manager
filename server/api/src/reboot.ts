import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ApiError, idSchema } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { requireDevice } from './device.ts';
import { requireAdmin } from './auth.ts';
import { targetDevice } from './admin.ts';
const commandSchema = z.object({ id: idSchema, connectionId: idSchema, bootId: z.int(), createdAtMs: z.number(), expiresAtMs: z.number(), state: z.string(), result: z.string(), receiptVersion: z.int(), receipt: z.unknown().optional(), permitIssued: z.boolean().default(false) });
const receiptSchema = z.object({ id: idSchema, version: z.int().positive(), state: z.enum(['ACCEPTED', 'ATTEMPTED', 'FAILED', 'UNKNOWN']), result: z.string().max(1000) }).strict();
export function rebootRoutes(app: FastifyInstance, ctx: Context): void {
  const grants = new Map<string, { id: string; connectionId: string; bootId: number; deadline: number }>();
  ctx.commandMessage = (deviceId, connectionId, raw) => {
    const input = z.object({ type: z.literal('reboot.permit.request'), commandId: idSchema, connectionId: idSchema }).strict().parse(raw);
    const grant = grants.get(deviceId); const current = ctx.realtime?.info(deviceId);
    const valid = grant?.id === input.commandId && grant.connectionId === connectionId && input.connectionId === connectionId && current?.id === connectionId && current.bootId === grant.bootId && ctx.monotonic() < grant.deadline;
    const row = ctx.store.get('command_receipts', `${deviceId}:${input.commandId}`, commandSchema);
    if (!valid || !row || row.permitIssued || !['DISPATCHED', 'ACCEPTED'].includes(row.state)) return { type: 'reboot.permit', commandId: input.commandId, connectionId, allowed: false, remainingMs: 0 };
    grants.delete(deviceId);
    ctx.store.put('command_receipts', `${deviceId}:${row.id}`, deviceId, { ...row, state: 'PERMITTED', permitIssued: true });
    return { type: 'reboot.permit', commandId: row.id, connectionId, allowed: true, remainingMs: Math.max(0, grant.deadline - ctx.monotonic()) };
  };
  app.post('/api/v1/admin/devices/:id/reboot', request => {
    requireAdmin(ctx, request, true); const device = targetDevice(ctx, request);
    const input = z.object({ commandId: idSchema, confirmed: z.literal(true) }).strict().parse(request.body);
    const key = `${device.id}:${input.commandId}`; const previous = ctx.store.get('command_receipts', key, commandSchema);
    if (previous) return { data: previous }; // A retry only queries; it never sends the command twice.
    const connection = ctx.realtime?.info(device.id);
    if (!connection || connection.bootId < 0) throw new ApiError(409, 'DEVICE_OFFLINE', '设备未在线或启动身份未知，不能重启');
    const row = { id: input.commandId, connectionId: connection.id, bootId: connection.bootId, createdAtMs: ctx.now(), expiresAtMs: ctx.now() + 10000, state: 'DISPATCHED', result: '已发出，等待设备', receiptVersion: 0 };
    ctx.store.put('command_receipts', key, device.id, row);
    grants.set(device.id, { id: row.id, connectionId: connection.id, bootId: connection.bootId, deadline: ctx.monotonic() + 10000 });
    if (!ctx.notify(device.id, { type: 'reboot.request', commandId: row.id, connectionId: row.connectionId, bootId: row.bootId, ttlMs: 10000 })) {
      grants.delete(device.id); const failed = { ...row, state: 'NOT_DELIVERED', result: '连接已断开，未送达；不会重试' };
      ctx.store.put('command_receipts', key, device.id, failed); return { data: failed };
    }
    return { data: row };
  });
  app.post('/api/v1/device/commands/receipts', request => {
    const device = requireDevice(ctx, request); const rows = z.object({ receipts: z.array(receiptSchema).max(200) }).strict().parse(request.body).receipts;
    const accepted: { id: string; version: number }[] = [];const orphaned: { id: string; version: number }[] = [];
    ctx.store.transaction(() => { for (const receipt of rows) {
      const key = `${device.id}:${receipt.id}`; const row = ctx.store.get('command_receipts', key, commandSchema);
      if (!row || (receipt.state === 'ATTEMPTED' && !row.permitIssued)) { orphaned.push({ id: receipt.id, version: receipt.version });continue; }
      if (row.receiptVersion === receipt.version && JSON.stringify(row.receipt) !== JSON.stringify(receipt)) throw new ApiError(409, 'RECEIPT_REUSE', '回执版本内容不一致');
      if (receipt.version > row.receiptVersion) ctx.store.put('command_receipts', key, device.id, { ...row, state: receipt.state, result: receipt.result, receiptVersion: receipt.version, receipt });
      accepted.push({ id: receipt.id, version: receipt.version });
    } });
    return { data: { accepted, orphaned } };
  });
  app.get('/api/v1/admin/devices/:id/commands', request => {
    requireAdmin(ctx, request); const device = targetDevice(ctx, request);
    const query = z.object({ page: z.coerce.number().int().min(1).max(100000).default(1) }).strict().parse(request.query);
    const boot = ctx.realtime?.info(device.id)?.bootId;
    const rows = ctx.store.list('command_receipts', commandSchema, device.id, 50, (query.page - 1) * 50).map(row => {
      if (row.state === 'ATTEMPTED' && boot !== undefined && boot > row.bootId) return { ...row, state: 'OBSERVED_LATER_BOOT', result: '已观察到后续系统启动，不能排除其他重启原因' };
      if (['DISPATCHED', 'PERMITTED', 'ACCEPTED', 'ATTEMPTED'].includes(row.state) && ctx.now() >= row.expiresAtMs) return { ...row, state: 'UNKNOWN', result: '结果未知；旧命令不会重试' };
      return row;
    });
    return { data: rows, page: query.page };
  });
}

import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import { z } from 'zod';
import { deviceSchema } from '../../contracts/index.ts';
import type { Context } from './device.ts';
import { requireDevice } from './device.ts';

interface Connection { id: string; socket: WebSocket; heartbeatAt: number; bootId: number }
export class RealtimeHub {
  private connections = new Map<string, Connection>();
  private ctx: Context;
  constructor(app: FastifyInstance, ctx: Context) {
    this.ctx = ctx;
    app.get('/api/v1/device/channel', { websocket: true, preValidation: async request => { requireDevice(ctx, request); } }, (socket, request) => {
      const device = requireDevice(ctx, request);
      this.disconnect(device.id);
      const connection: Connection = { id: randomUUID(), socket, heartbeatAt: ctx.now(), bootId: -1 };
      this.connections.set(device.id, connection);
      socket.send(JSON.stringify({ type: 'connected', connectionId: connection.id, serverAtMs: ctx.now() }));
      socket.on('message', raw => {
        try {
          const input = z.object({ type: z.literal('heartbeat'), bootId: z.int().min(-1), syncError: z.string().max(300).default('') }).strict().parse(JSON.parse(raw.toString()) as unknown);
          connection.heartbeatAt = ctx.now(); connection.bootId = input.bootId;
          const current = ctx.store.get('devices', device.id, deviceSchema);
          if (!current) { socket.close(1008, 'identity unavailable'); return; }
          ctx.store.put('devices', device.id, device.id, { ...current, lastSeenAt: ctx.now(), status: { ...current.status, syncError: input.syncError } });
          socket.send(JSON.stringify({ type: 'heartbeat.ack', connectionId: connection.id, serverAtMs: ctx.now() }));
          const pending = ctx.store.get('notification_outbox', device.id, z.object({ revision: z.number() }));
          if (pending) {
            socket.send(JSON.stringify({ type: 'config.changed', serverRevision: pending.revision }));
            ctx.store.delete('notification_outbox', device.id);
          }
        } catch { socket.close(1008, 'message unavailable'); }
      });
      const forget = () => { if (this.connections.get(device.id) === connection) this.connections.delete(device.id); };
      socket.on('close', forget); socket.on('error', forget);
    });
    const timer = setInterval(() => {
      for (const [deviceId, connection] of this.connections) if (ctx.now() - connection.heartbeatAt > 90_000) this.disconnect(deviceId);
    }, 30_000);
    timer.unref();
    app.addHook('preClose', async () => { clearInterval(timer); for (const id of this.connections.keys()) this.disconnect(id); });
  }
  info(deviceId: string): { id: string; bootId: number } | null {
    const connection = this.connections.get(deviceId);
    return connection && connection.socket.readyState === 1 && this.ctx.now() - connection.heartbeatAt <= 90_000 ? { id: connection.id, bootId: connection.bootId } : null;
  }
  send(deviceId: string, message: unknown): boolean {
    const connection = this.connections.get(deviceId);
    if (!connection || !this.info(deviceId)) return false;
    try { connection.socket.send(JSON.stringify(message)); return true; }
    catch { this.disconnect(deviceId); return false; }
  }
  disconnect(deviceId: string): void {
    const connection = this.connections.get(deviceId);
    this.connections.delete(deviceId); connection?.socket.terminate();
  }
}

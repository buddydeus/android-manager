import { createHash, createPublicKey, randomBytes, randomUUID, timingSafeEqual, verify } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { ApiError, candidateSchema, currentSchema, challengeInput, registerSchema, deviceSchema } from '../../contracts/index.ts';
import type { Device, CurrentConfig } from '../../contracts/index.ts';
import type { Store } from './db.ts';

export interface Context {
  store: Store; now: () => number; enrollmentHash: string; origin: string;
  notify: (deviceId: string, message: unknown) => boolean;
  disconnect: (deviceId: string) => void;
}
export function digest(value: string): string { return createHash('sha256').update(value).digest('hex'); }
export function secretEquals(value: string, expectedHash: string): boolean { return timingSafeEqual(Buffer.from(digest(value), 'hex'), Buffer.from(expectedHash, 'hex')); }
const credentialSchema = z.object({ deviceId: z.string() });
const challengeSchema = challengeInput.extend({ challengeId: z.string(), nonce: z.string(), expiresAt: z.number() });
export function requireDevice(ctx: Context, request: FastifyRequest): Device {
  const auth = request.headers.authorization;
  const credential = auth?.startsWith('Bearer ') ? ctx.store.get('device_credentials', digest(auth.slice(7)), credentialSchema) : null;
  const device = credential && ctx.store.get('devices', credential.deviceId, deviceSchema);
  if (!device) throw new ApiError(401, 'DEVICE_AUTH', '设备身份验证失败');
  return device;
}
function requireEnrollment(ctx: Context, request: FastifyRequest): void {
  const value = request.headers['x-enrollment-key'];
  if (typeof value !== 'string' || !secretEquals(value, ctx.enrollmentHash)) throw new ApiError(401, 'ENROLLMENT_AUTH', '设备接入验证失败');
}
function enroll(ctx: Context, input: z.infer<typeof registerSchema>): { deviceId: string; token: string } {
  return ctx.store.transaction(() => {
    const c = ctx.store.get('challenges', input.challengeId, challengeSchema);
    if (!c || c.expiresAt < ctx.now() || c.installId !== input.installId || c.publicKey !== input.publicKey) throw new ApiError(401, 'CHALLENGE', '注册挑战无效或已过期');
    let valid = false;
    try {
      const key = createPublicKey({ key: Buffer.from(input.publicKey, 'base64'), format: 'der', type: 'spki' });
      valid = key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails?.namedCurve === 'prime256v1' && verify('sha256', Buffer.from(`${c.challengeId}\n${c.nonce}\n${c.installId}\n${c.publicKey}`), key, Buffer.from(input.signature, 'base64'));
    } catch { valid = false; }
    if (!valid) throw new ApiError(401, 'CHALLENGE', '注册签名验证失败');
    const row = ctx.store.db.prepare("SELECT data FROM devices WHERE json_extract(data,'$.installId')=?").get(input.installId);
    const existing = row ? deviceSchema.parse(JSON.parse(z.object({ data: z.string() }).parse(row).data) as unknown) : null;
    if (existing && existing.publicKey !== input.publicKey) throw new ApiError(409, 'IDENTITY_CHANGED', '设备密钥与现有注册不匹配');
    const device: Device = existing ?? { id: randomUUID(), installId: input.installId, publicKey: input.publicKey, model: input.model, apiLevel: input.apiLevel, versionCode: input.versionCode, versionName: input.versionName, registeredAt: ctx.now(), lastSeenAt: null, status: {} };
    const token = randomBytes(32).toString('base64url');
    ctx.store.put('devices', device.id, device.id, device);
    ctx.store.deleteDevice('device_credentials', device.id);
    ctx.store.put('device_credentials', digest(token), device.id, { deviceId: device.id });
    ctx.store.delete('challenges', input.challengeId);
    ctx.disconnect(device.id);
    return { deviceId: device.id, token };
  });
}
export function acceptCandidate(ctx: Context, deviceId: string, input: unknown): CurrentConfig | null {
  return ctx.store.transaction(() => {
    let current = ctx.store.get('device_configs', deviceId, currentSchema);
    if (input === null || input === undefined) return current;
    const candidate = candidateSchema.parse(input);
    const key = `${deviceId}:${candidate.mutationId}`;
    const prior = ctx.store.get('config_mutations', key, candidateSchema);
    if (prior && JSON.stringify(prior) !== JSON.stringify(candidate)) throw new ApiError(409, 'MUTATION_REUSE', '同一变更编号内容不一致');
    if (prior) return current;
    ctx.store.put('config_mutations', key, deviceId, candidate);
    if (!current || candidate.modifiedAtMs > current.modifiedAtMs) {
      current = { ...candidate, serverRevision: (current?.serverRevision ?? 0) + 1 };
      ctx.store.put('device_configs', deviceId, deviceId, current);
      ctx.store.put('notification_outbox', deviceId, deviceId, { revision: current.serverRevision });
    }
    return current;
  });
}
export function deviceRoutes(app: FastifyInstance, ctx: Context): void {
  app.post('/api/v1/device/challenges', { config: { enrollment: true } }, request => {
    requireEnrollment(ctx, request);
    const input = challengeInput.parse(request.body);
    ctx.store.db.prepare("DELETE FROM challenges WHERE json_extract(data,'$.expiresAt') < ?").run(ctx.now());
    const c = { ...input, challengeId: randomUUID(), nonce: randomBytes(32).toString('base64'), expiresAt: ctx.now() + 120_000 };
    ctx.store.put('challenges', c.challengeId, input.installId, c);
    return { data: { challengeId: c.challengeId, nonce: c.nonce } };
  });
  app.post('/api/v1/device/register', request => { requireEnrollment(ctx, request); return { data: enroll(ctx, registerSchema.parse(request.body)) }; });
  app.get('/api/v1/device/time', request => { requireDevice(ctx, request); return { data: { nowMs: ctx.now() } }; });
  app.post('/api/v1/device/sync', request => {
    const device = requireDevice(ctx, request);
    const input = z.object({ candidate: candidateSchema.nullish() }).strict().parse(request.body);
    const previous = ctx.store.get('device_configs', device.id, currentSchema);
    const current = acceptCandidate(ctx, device.id, input.candidate);
    if (current && current.serverRevision !== previous?.serverRevision) ctx.notify(device.id, { type: 'config.changed', serverRevision: current.serverRevision });
    return { data: { current } };
  });
}
